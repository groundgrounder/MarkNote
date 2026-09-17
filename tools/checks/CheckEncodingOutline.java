import com.marknote.app.data.DecodedText;
import com.marknote.app.data.TextEncoding;
import com.marknote.app.ui.editor.MarkdownSyntaxKt;
import com.marknote.app.ui.editor.Heading;

import java.nio.charset.Charset;
import java.util.List;

/**
 * TextEncoding 与 parseOutline 的纯逻辑断言。不需要模拟器，秒级完成。
 *
 * 为什么走 JVM 而不是 app/src/test：那个源集要引 JUnit，引就得联网拉包；
 * 这两个函数本身零 Android 依赖：它们在 TextEncoding.kt 与 MarkdownSyntax.kt 里，
 * 后者一个 import 都没有（早先 parseOutline 住在 EditorViewModel.kt，那里依赖 Compose，
 * 只靠「静态调用不触达」才跑得起来，已经搬走了）。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckEncodingOutline {

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

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static void main(String[] args) {
        bomStrippedInSnippet();
        snippetStillHandlesTruncatedTail();
        snippetWithoutBomUnchanged();
        outlineBasics();
        outlineBacktickFence();
        outlineTildeFence();
        outlineFenceLengthAndInfoString();
        outlineRealHeadingAfterFence();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 列表摘要也要剥 BOM：不剥的话首字符是零宽 U+FEFF，和编辑器里的正文对不上 */
    static void bomStrippedInSnippet() {
        byte[] bom = bytes(0xEF, 0xBB, 0xBF);
        byte[] body = "# 标题".getBytes(Charset.forName("UTF-8"));

        DecodedText full = TextEncoding.INSTANCE.decode(concat(bom, body));
        DecodedText snippet = TextEncoding.INSTANCE.decodeTruncated(concat(bom, body));

        check("decode() 剥 BOM", full.getText().equals("# 标题"));
        check("decodeTruncated() 也剥 BOM", snippet.getText().equals("# 标题"));
        check("decodeTruncated() 记得带 BOM", snippet.getEncoding().getWithBom());
    }

    /** 末尾被切开的半个多字节字符仍要能解出来（这是 decodeTruncated 存在的理由） */
    static void snippetStillHandlesTruncatedTail() {
        Charset gb = Charset.forName("GB18030");
        byte[] whole = "中文".getBytes(gb);
        byte[] cut = new byte[whole.length - 1];
        System.arraycopy(whole, 0, cut, 0, cut.length);

        DecodedText r = TextEncoding.INSTANCE.decodeTruncated(cut);
        check("GBK 半截字节：解出「中」而不是乱码", r.getText().equals("中"));

        // UTF-16LE 带 BOM：剥 BOM 后长度是奇数（半截）也要能解出整字符
        byte[] u16 = concat(bytes(0xFF, 0xFE), "中文".getBytes(Charset.forName("UTF-16LE")));
        byte[] u16cut = new byte[u16.length - 1];
        System.arraycopy(u16, 0, u16cut, 0, u16cut.length);
        DecodedText r2 = TextEncoding.INSTANCE.decodeTruncated(u16cut);
        check("UTF-16LE 带 BOM 且半截：解出「中」", r2.getText().equals("中"));
    }

    /** 不带 BOM 的普通文件行为不变 */
    static void snippetWithoutBomUnchanged() {
        DecodedText r = TextEncoding.INSTANCE.decodeTruncated("# 标题".getBytes(Charset.forName("UTF-8")));
        check("无 BOM：原样", r.getText().equals("# 标题") && !r.getEncoding().getWithBom());
    }

    static void outlineBasics() {
        List<Heading> hs = MarkdownSyntaxKt.parseOutline("# 一\n正文\n## 二\n");
        check("基本标题：数量", hs.size() == 2);
        check("基本标题：层级", hs.get(0).getLevel() == 1 && hs.get(1).getLevel() == 2);
        check("基本标题：文字", hs.get(0).getTitle().equals("一") && hs.get(1).getTitle().equals("二"));
        check("基本标题：偏移", hs.get(1).getOffset() == "# 一\n正文\n".length());
    }

    static void outlineBacktickFence() {
        List<Heading> hs = MarkdownSyntaxKt.parseOutline("```\n# 不是标题\n```\n# 是标题\n");
        check("``` 围栏里的 # 不进大纲", hs.size() == 1 && hs.get(0).getTitle().equals("是标题"));
    }

    /** 新增：~~~ 围栏此前完全不认，里面的 # 会跑进大纲 */
    static void outlineTildeFence() {
        List<Heading> hs = MarkdownSyntaxKt.parseOutline("~~~\n# 不是标题\n~~~\n# 是标题\n");
        check("~~~ 围栏里的 # 不进大纲", hs.size() == 1 && hs.get(0).getTitle().equals("是标题"));
    }

    /** 围栏必须「同种字符 + 不短于开头」才闭合，且信息串不能当闭合行 */
    static void outlineFenceLengthAndInfoString() {
        // ~~~ 块里出现 ``` 不该闭合，所以后面那个 # 仍在代码块里
        List<Heading> hs = MarkdownSyntaxKt.parseOutline("~~~\n```\n# 还在代码块里\n~~~\n# 出来了\n");
        check("``` 不会闭合 ~~~ 块", hs.size() == 1 && hs.get(0).getTitle().equals("出来了"));

        // 四个反引号开的块，三个反引号关不掉
        List<Heading> hs2 = MarkdownSyntaxKt.parseOutline("````\n```\n# 还在块里\n````\n# 出来了\n");
        check("短围栏关不掉长围栏", hs2.size() == 1 && hs2.get(0).getTitle().equals("出来了"));

        // 带信息串的开头照样算围栏
        List<Heading> hs3 = MarkdownSyntaxKt.parseOutline("```kotlin\n# 不是标题\n```\n# 是标题\n");
        check("带信息串的开头也算围栏", hs3.size() == 1 && hs3.get(0).getTitle().equals("是标题"));
    }

    static void outlineRealHeadingAfterFence() {
        List<Heading> hs = MarkdownSyntaxKt.parseOutline("# 前\n```\ncode\n```\n## 后\n```\nmore\n```\n### 末\n");
        check("多个围栏之间穿插的标题都在", hs.size() == 3
                && hs.get(0).getTitle().equals("前")
                && hs.get(1).getTitle().equals("后")
                && hs.get(2).getTitle().equals("末"));
    }
}
