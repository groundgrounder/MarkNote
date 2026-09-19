import com.marknote.app.data.TextFileTypesKt;

/**
 * 「哪些文件算文本文件」的纯逻辑断言 —— 决定哪些条目进得了文件夹浏览的列表。
 *
 * 最后那一节是**实测样本回归**：下面每个 (文件名, MIME) 都对应
 * 2026-09-19 在 API 36 模拟器上查 ExternalStorageProvider 得到的真实取值。
 * 以后改白名单/黑名单时，这一节就是「有没有把原来能看见的文件弄丢」的刻度。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckTextFileTypes {

    static final String DIR = "vnd.android.document/directory";

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name);
        }
    }

    /** 默认开关状态（不显示隐藏文件）下的判定 */
    static boolean browsable(String name, String mime) {
        return TextFileTypesKt.isBrowsableEntry(name, mime, false);
    }

    public static void main(String[] args) {
        // ---------------- MIME 判定 ----------------
        check("text/markdown 是文本", TextFileTypesKt.isTextMime("text/markdown"));
        check("text/plain 是文本", TextFileTypesKt.isTextMime("text/plain"));
        check("text/x-python 是文本", TextFileTypesKt.isTextMime("text/x-python"));
        check("大小写不敏感", TextFileTypesKt.isTextMime("TEXT/Plain"));
        check("octet-stream 不是文本", !TextFileTypesKt.isTextMime("application/octet-stream"));
        check("image/png 不是文本", !TextFileTypesKt.isTextMime("image/png"));
        check("空串不是文本", !TextFileTypesKt.isTextMime(""));
        check("前缀不同不算（textual/plain）", !TextFileTypesKt.isTextMime("textual/plain"));

        // ---------------- 隐藏文件判定 ----------------
        check(".git 是隐藏", TextFileTypesKt.isHiddenName(".git"));
        check(".hidden.md 是隐藏", TextFileTypesKt.isHiddenName(".hidden.md"));
        check("note.md 不隐藏", !TextFileTypesKt.isHiddenName("note.md"));
        check("名字中间的点不算隐藏", !TextFileTypesKt.isHiddenName("a.b"));

        // ---------------- 哪些条目进列表 ----------------
        check("md 收", browsable("a.md", "text/markdown"));
        check("markdown 收", browsable("a.markdown", "text/plain"));
        check("txt 收", browsable("a.txt", "text/plain"));
        check("大写后缀也收", browsable("README.MD", "application/octet-stream"));
        check("text/* 收", browsable("无后缀", "text/x-markdown"));
        check("目录一律收", browsable("notes", DIR));
        check("png 不收", !browsable("a.png", "image/png"));
        check("apk 不收", !browsable("a.apk", "application/vnd.android.package-archive"));
        check("没有扩展名的二进制不收", !browsable("data", "application/octet-stream"));
        check("名字以点结尾时按 MIME 判（文本收）", browsable("notes.", "text/plain"));

        // provider 谎报 text/plain 时，扩展名黑名单必须一票否决 —— 否则图片、压缩包会混进列表，
        // 点开是一屏乱码（第三方网盘上真会这么报）
        check("黑名单压过 MIME：png 配 text/plain 不收", !browsable("pic.png", "text/plain"));
        check("黑名单压过 MIME：zip 配 text/plain 不收", !browsable("a.zip", "text/plain"));
        check("黑名单压过 MIME：apk 配 text/plain 不收", !browsable("a.apk", "text/plain"));
        check("黑名单不影响别的扩展名", browsable("a.md", "text/plain"));

        // 隐藏文件：默认（开关关）滤掉，开关打开就回来 —— 目录也一起滤
        check("隐藏的 md 默认不收", !browsable(".hidden.md", "text/markdown"));
        check("隐藏的 md 打开开关后收", TextFileTypesKt.isBrowsableEntry(".hidden.md", "text/markdown", true));
        check("隐藏目录默认不收", !browsable(".git", DIR));
        check("隐藏目录打开开关后收", TextFileTypesKt.isBrowsableEntry(".git", DIR, true));
        check("非隐藏条目不受开关影响",
                browsable("note.md", "text/markdown")
                        && TextFileTypesKt.isBrowsableEntry("note.md", "text/markdown", true));

        // ---------------- 扩展名解析的边界（别崩、别误判） ----------------
        // 名字以点结尾或只有一个点：取不出扩展名 → 退回按 MIME 判；而「以点开头」一律先按隐藏处理
        check("单个点（.）当隐藏处理", !browsable(".", "text/plain"));
        check("两个点（..）当隐藏处理", !browsable("..", "text/plain"));
        check("空名字不崩且不收", !browsable("", "application/octet-stream"));
        check("空名字配文本 MIME 时按 MIME 收", browsable("", "text/plain"));
        check("隐藏的 md，扩展名照样认得出来（开关开时收）",
                TextFileTypesKt.isBrowsableEntry(".md", "application/octet-stream", true));
        check("大写扩展名认得出", browsable("A.MD", "application/octet-stream"));
        check("混合大小写扩展名认得出", browsable("note.MarkDown", "application/octet-stream"));
        check("名字里多个点时看最后一段（a.tar.gz 走黑名单）", !browsable("a.tar.gz", "text/plain"));
        check("名字里多个点时看最后一段（a.bak.md 收）", browsable("a.bak.md", "application/octet-stream"));

        // ---------------- 黑名单/白名单覆盖面 ----------------
        // 这一组是给「以后手滑把某个扩展名加进黑名单」准备的 —— 常见笔记文件必须一直是收的。
        // 配一个二进制 MIME 来问，把判据完全压在扩展名上、绕开 MIME 的兜底。
        String[] mustBeAccepted = {"a.md", "a.markdown", "a.txt", "A.MD", "A.Markdown", "A.TXT"};
        for (String name : mustBeAccepted) {
            check("常见笔记文件不被黑名单误伤：" + name,
                    browsable(name, "application/octet-stream"));
        }
        // 反向：这些一定不能收，哪怕 provider 把它们谎报成 text/plain。
        // ⚠️ 别把 .svg 加进来 —— 它本来就是文本（XML），配 text/plain 时收是对的；
        // 平时它报的是 image/svg+xml，自然也不会进列表。
        String[] mustBeRejected = {
                "a.png", "a.jpg", "a.jpeg", "a.gif", "a.webp", "a.heic", "a.tiff",
                "a.pdf", "a.doc", "a.docx", "a.xls", "a.xlsx", "a.ppt", "a.pptx", "a.epub",
                "a.zip", "a.rar", "a.7z", "a.gz", "a.tar", "a.bz2", "a.xz",
                "a.apk", "a.jar", "a.dex", "a.so", "a.exe", "a.dll",
                "a.mp3", "a.m4a", "a.flac", "a.wav", "a.opus",
                "a.mp4", "a.mkv", "a.mov", "a.webm", "a.avi",
                "a.ttf", "a.otf", "a.woff2",
                "a.bin", "a.dat", "a.db", "a.sqlite",
        };
        for (String name : mustBeRejected) {
            check("二进制不被误收：" + name, !browsable(name, "text/plain"));
        }

        // ---------------- 实测样本回归（API 36 / ExternalStorageProvider） ----------------
        String[][] samples = {
                // 文件名, MIME, 默认开关下是否该出现在列表里
                {"note.md", "text/markdown", "1"},
                {"README.MD", "text/markdown", "1"},        // 扩展名会被 provider 转小写
                {"draft.txt", "text/plain", "1"},
                {"page.html", "text/html", "1"},
                {"script.py", "text/x-python", "1"},
                {".hidden.md", "text/markdown", "0"},       // 隐藏
                {"config.json", "application/json", "0"},
                {"Makefile", "application/octet-stream", "0"},   // 无扩展名 → 认不出来，刻意不收
                {".gitignore", "application/octet-stream", "0"},
                {"pic.png", "image/png", "0"},
                {"doc.pdf", "application/pdf", "0"},
                {"app.apk", "application/vnd.android.package-archive", "0"},
                {"archive.zip", "application/zip", "0"},
                {"archive.tar.gz", "application/gzip", "0"},
                {"blob", "application/octet-stream", "0"},
                {"data.bin", "application/octet-stream", "0"},
        };
        for (String[] sample : samples) {
            boolean want = sample[2].equals("1");
            boolean got = browsable(sample[0], sample[1]);
            check("实测样本 " + sample[0] + "(" + sample[1] + ") → " + (want ? "收" : "不收"),
                    got == want);
        }

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
