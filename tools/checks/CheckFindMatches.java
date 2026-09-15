import com.marknote.app.ui.editor.EditorScreenKt;

import java.util.List;

/**
 * findMatches 的纯逻辑断言。
 *
 * 这里守的是一条**口径一致性**：搜索面板显示的「n/m」和「全部替换」实际换掉的处数
 * 必须对得上。两边都按非重叠推进才一致——早期 findMatches 用 `indexOf(query, i + 1)`，
 * 会把重叠命中也算进去（`aaaa` 里搜 `aa` 报 3 处，而 replaceAll 只换 2 处）。
 *
 * 用法：tools/run_checks.sh
 */
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
        return EditorScreenKt.findMatches(text, query);
    }

    public static void main(String[] args) {
        check("空 query 不算命中", m("abc", "").isEmpty());
        check("空文本不算命中", m("", "a").isEmpty());

        check("普通命中：3 处", m("a b a b a", "a").size() == 3);
        check("普通命中：1 处", m("abc", "abc").size() == 1);
        check("整体等于：1 处而不是 3 处", m("aaa", "aaa").size() == 1);

        // 核心：重叠处不重复计数，才能与 replaceAll 对齐
        check("自重叠 query：aaaa 搜 aa = 2 处（非重叠）", m("aaaa", "aa").size() == 2);
        check("自重叠 query：abab 搜 abab = 1 处", m("abababab", "abab").size() == 2);
        check("aaaaa 搜 aa = 2 处（末尾残一个 a 不算）", m("aaaaa", "aa").size() == 2);

        // 命中区间本身也要对
        List<?> r = m("xAByAB", "AB");
        check("命中区间正确", r.size() == 2 && r.get(0).toString().equals("1..2")
                && r.get(1).toString().equals("4..5"));

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
