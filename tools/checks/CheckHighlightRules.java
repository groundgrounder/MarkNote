import com.marknote.app.ui.editor.HighlightKind;
import com.marknote.app.ui.editor.HighlightRule;
import com.marknote.app.ui.editor.HighlightRulesKt;
import com.marknote.app.ui.editor.HighlightSpan;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class CheckHighlightRules {

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

    static String spans(String text, HighlightKind kind) {
        StringBuilder sb = new StringBuilder();
        for (HighlightSpan s : HighlightRulesKt.highlightRanges(text)) {
            if (s.getKind() != kind) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(s.getStart()).append("-").append(s.getEndExclusive());
        }
        return sb.toString();
    }

    static void checkSpans(String label, String text, HighlightKind kind, String expected) {
        String actual = spans(text, kind);
        check(label + " → [" + actual + "]", actual.equals(expected));
    }

    public static void main(String[] args) {

        Set<HighlightKind> covered = EnumSet.noneOf(HighlightKind.class);
        for (HighlightRule rule : HighlightRulesKt.getHighlightRules()) {
            covered.add(rule.getKind());
        }
        check("每一种高亮元素都有规则覆盖（" + covered.size() + "/"
                + HighlightKind.values().length + "）",
                covered.size() == HighlightKind.values().length);

        checkSpans("未转义的链接命中", "见 [文档](http://x)", HighlightKind.LINK, "2-16");
        checkSpans("整段转义的链接不命中", "见 \\[文档\\](http://x)", HighlightKind.LINK, "");
        checkSpans("只转义闭合方括号也不命中", "见 [文档\\](http://x)", HighlightKind.LINK, "");
        checkSpans("同一行两个链接都命中", "[a](x) [b](y)", HighlightKind.LINK, "0-6,7-13");
        checkSpans("转义的那个不干扰后面真链接",
                "\\[不是链接\\](x) 和 [是链接](y)", HighlightKind.LINK, "14-22");

        checkSpans("未转义的行内代码命中", "用 `code` 表示", HighlightKind.INLINE_CODE, "2-8");
        checkSpans("整段转义的行内代码不命中", "用 \\`code\\` 表示", HighlightKind.INLINE_CODE, "");
        checkSpans("两个行内代码段都命中", "`a` 与 `b`", HighlightKind.INLINE_CODE, "0-3,6-9");

        checkSpans("未转义的加粗命中", "**粗**", HighlightKind.BOLD, "0-5");
        checkSpans("整段转义的加粗不命中", "\\*\\*粗\\*\\*", HighlightKind.BOLD, "");
        checkSpans("未转义的斜体命中", "*斜*", HighlightKind.ITALIC, "0-3");
        checkSpans("整段转义的斜体不命中", "\\*斜\\*", HighlightKind.ITALIC, "");
        checkSpans("删除线命中", "~~删~~", HighlightKind.STRIKETHROUGH, "0-5");
        checkSpans("转义的删除线不命中", "\\~~删~~", HighlightKind.STRIKETHROUGH, "");

        String mixed = "**粗** 和 *斜*";
        checkSpans("加粗与斜体共存：加粗区间", mixed, HighlightKind.BOLD, "0-5");
        checkSpans("加粗与斜体共存：斜体只圈自己", mixed, HighlightKind.ITALIC, "8-11");

        checkSpans("标题行仍命中", "# 标题", HighlightKind.HEADING, "0-4");
        checkSpans("引用行仍命中", "> 引用", HighlightKind.QUOTE, "0-4");
        checkSpans("分割线仍命中", "---", HighlightKind.HORIZONTAL_RULE, "0-3");
        checkSpans("无序列表标记只给标记上色", "- 列表", HighlightKind.LIST_MARKER, "0-1");
        checkSpans("有序列表标记只给标记上色", "1. 有序", HighlightKind.LIST_MARKER, "0-2");
        checkSpans("缩进的列表标记带上缩进", "  - 嵌套", HighlightKind.LIST_MARKER, "0-3");

        checkSpans("行内的星号不会被当成列表标记", "a * b", HighlightKind.LIST_MARKER, "");

        String fenced = "```\ncode\n```";
        checkSpans("围栏代码块整段命中", fenced, HighlightKind.CODE_BLOCK, "0-12");
        String tildeFenced = "~~~\ncode\n~~~";
        checkSpans("波浪线围栏代码块整段命中", tildeFenced, HighlightKind.CODE_BLOCK, "0-12");

        checkSpans("围栏内的标题写法仍按行首规则上色（叠加式的取舍）",
                "```\n# 不是标题\n```", HighlightKind.HEADING, "4-10");

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
