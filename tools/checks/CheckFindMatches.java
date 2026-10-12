import com.marknote.app.ui.editor.MarkdownSyntaxKt;

import java.util.List;

public class CheckFindMatches {

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

    static List<?> m(String text, String query) {
        return MarkdownSyntaxKt.findMatches(text, query);
    }

    public static void main(String[] args) {
        check("空 query 不算命中", m("abc", "").isEmpty());
        check("空文本不算命中", m("", "a").isEmpty());

        check("普通命中：3 处", m("a b a b a", "a").size() == 3);
        check("普通命中：1 处", m("abc", "abc").size() == 1);
        check("整体等于：1 处而不是 3 处", m("aaa", "aaa").size() == 1);

        check("自重叠 query：aaaa 搜 aa = 2 处（非重叠）", m("aaaa", "aa").size() == 2);
        check("自重叠 query：abab 搜 abab = 1 处", m("abababab", "abab").size() == 2);
        check("aaaaa 搜 aa = 2 处（末尾残一个 a 不算）", m("aaaaa", "aa").size() == 2);

        List<?> r = m("xAByAB", "AB");
        check("命中区间正确", r.size() == 2 && r.get(0).toString().equals("1..2")
                && r.get(1).toString().equals("4..5"));

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
