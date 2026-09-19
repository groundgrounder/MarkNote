import com.marknote.app.data.FolderEntry;
import com.marknote.app.data.FolderListingKt;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹浏览的纯逻辑断言：怎么排序、面包屑怎么拼。
 *
 * 「哪些条目进列表」那部分挪到了 CheckTextFileTypes —— 那份口径同时被系统文件选择器用着
 * （见 PICKER_MIME_TYPES），跟选择器放一起测才对得上。
 *
 * 这条线最容易出的错是「看起来能用、实际顺序每次刷新都在变」，不报错、只在用的时候别扭，
 * 所以在这里钉住。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckFolderListing {

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

    static FolderEntry dir(String name) {
        return new FolderEntry(name, "uri:" + name, true);
    }

    static FolderEntry file(String name) {
        return new FolderEntry(name, "uri:" + name, false);
    }

    static String names(List<FolderEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (FolderEntry e : entries) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getName());
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        // ---------------- 排序：目录在前，名字大小写不敏感 ----------------
        List<FolderEntry> raw = new ArrayList<>();
        raw.add(file("zebra.md"));
        raw.add(dir("notes"));
        raw.add(file("Apple.md"));
        raw.add(dir("archive"));
        raw.add(file("mango.md"));
        String sorted = names(FolderListingKt.sortFolderEntries(raw));
        check("目录在前 + 名字大小写不敏感 → " + sorted,
                sorted.equals("archive,notes,Apple.md,mango.md,zebra.md"));

        List<FolderEntry> sameName = new ArrayList<>();
        sameName.add(new FolderEntry("a.md", "uri:2", false));
        sameName.add(new FolderEntry("a.md", "uri:1", false));
        List<FolderEntry> twice = FolderListingKt.sortFolderEntries(sameName);
        String firstPass = names(twice);
        List<FolderEntry> again = FolderListingKt.sortFolderEntries(twice);
        check("同名时顺序稳定（按 uri 兜底）", firstPass.equals("a.md,a.md")
                && again.get(0).getUri().equals(twice.get(0).getUri()));
        check("排序不改动入参", raw.size() == 5 && raw.get(0).getName().equals("zebra.md"));

        // ---------------- 面包屑 ----------------
        check("外部存储的目录 id → 末段名", "notes".equals(FolderListingKt.folderDisplayName("primary:Documents/notes")));
        check("根目录 id → 末段名", "Documents".equals(FolderListingKt.folderDisplayName("primary:Documents")));
        check("媒体库 id → 末段名", "1000000023".equals(FolderListingKt.folderDisplayName("msf:1000000023")));
        check("空 id → null", FolderListingKt.folderDisplayName("") == null);


        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
