import com.marknote.app.ui.editor.MarkdownEditKt;
import com.marknote.app.ui.editor.MarkdownSyntaxKt;
import com.marknote.app.ui.editor.EditResult;

/**
 * 编辑行为（回车续列表、Tab 缩进）的纯逻辑断言。
 *
 * 「按了回车之后光标该在哪、文本变成什么」是读代码最容易看走眼的一类逻辑，
 * 而这些函数都在 android-free 的 MarkdownEdit.kt 里，所以能在这里毫秒级跑一遍。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckMarkdownEdit {

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

    /** 模拟「在 lineBefore 末尾按回车」，返回续行结果（null 表示不处理） */
    static EditResult enter(String lineBefore) {
        String text = lineBefore + "\n";
        return MarkdownEditKt.continueListOnNewline(text, text.length());
    }

    static String textOf(EditResult edit) {
        return edit == null ? "<null>" : edit.getText();
    }

    static void eq(String name, EditResult edit, String expectedText) {
        if (edit != null && edit.getText().equals(expectedText)) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name + "\n      期望: " + expectedText + "\n      实际: " + textOf(edit));
        }
    }

    static void caret(String name, EditResult edit, int expectedCaret) {
        if (edit != null && edit.getSelectionStart() == expectedCaret && edit.getSelectionEnd() == expectedCaret) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name + "\n      期望光标: " + expectedCaret
                    + "\n      实际: " + (edit == null ? "<null>" : edit.getSelectionStart()));
        }
    }

    public static void main(String[] args) {
        // ---------------- 回车续列表 ----------------
        eq("- foo 回车续上", enter("- foo"), "- foo\n- ");
        caret("- foo 续上后光标在标记之后", enter("- foo"), 8);
        eq("* foo 也认", enter("* foo"), "* foo\n* ");
        eq("+ foo 也认", enter("+ foo"), "+ foo\n+ ");
        eq("缩进保留", enter("  - foo"), "  - foo\n  - ");
        eq("有序列表递增", enter("1. foo"), "1. foo\n2. ");
        eq("有序列表 9 → 10", enter("9. foo"), "9. foo\n10. ");
        eq("有序列表分隔符保持 )", enter("3) foo"), "3) foo\n4) ");
        eq("引用续上", enter("> foo"), "> foo\n> ");
        eq("嵌套引用续上", enter(">> foo"), ">> foo\n>> ");

        // 复选框：新条目一律未勾选
        eq("- [ ] foo 回车续上复选框", enter("- [ ] foo"), "- [ ] foo\n- [ ] ");
        eq("- [x] foo 续出来是未勾选", enter("- [x] foo"), "- [x] foo\n- [ ] ");
        eq("- [X] 大写也续", enter("- [X] foo"), "- [X] foo\n- [ ] ");

        // 空条目：再按一次回车结束列表（标记被删掉，新行留空）
        eq("空条目结束列表", enter("- "), "\n");
        caret("结束列表后光标在空行开头", enter("- "), 0);
        eq("空有序条目结束列表", enter("1. "), "\n");
        eq("空引用结束引用", enter("> "), "\n");

        // 不该动的情况
        check("普通段落不处理", enter("正文一行") == null);
        check("标题不处理", enter("# 标题") == null);
        check("空行不处理", enter("") == null);
        check("星号强调不误判", enter("**加粗**") == null);
        check("围栏代码块里的 `- ` 不续行",
                MarkdownEditKt.continueListOnNewline("```\n- foo\n", 10) == null);
        check("围栏闭合后恢复处理",
                MarkdownEditKt.continueListOnNewline("```\nx\n```\n- foo\n", 16) != null);
        check("不在回车之后调用则不处理",
                MarkdownEditKt.continueListOnNewline("- foo", 3) == null);

        // ---------------- Tab 缩进 ----------------
        EditResult ind = MarkdownEditKt.indentLines("a\nb\nc", 0, 0, false);
        eq("光标处整行缩进", ind, "  a\nb\nc");
        caret("缩进后光标右移两格", ind, 2);

        EditResult multi = MarkdownEditKt.indentLines("a\nb\nc", 0, 5, false);
        eq("选中多行整块缩进", multi, "  a\n  b\n  c");
        check("多行缩进后选区起点右移 2（0 → 2）",
                multi != null && multi.getSelectionStart() == 2);
        check("多行缩进后选区终点右移 6（0..5 → 2..11）",
                multi != null && multi.getSelectionEnd() == 11);

        // 选区正好停在行首：不该把下一行也缩进
        EditResult firstLineOnly = MarkdownEditKt.indentLines("a\nb", 0, 2, false);
        eq("选区停在行首时不含下一行", firstLineOnly, "  a\nb");

        EditResult out = MarkdownEditKt.indentLines("    a", 5, 5, true);
        eq("反缩进最多吃两格", out, "  a");
        caret("反缩进后光标左移两格", out, 3);

        EditResult outOne = MarkdownEditKt.indentLines(" a", 2, 2, true);
        eq("只有一格空格时吃掉一格", outOne, "a");
        check("行首没有空格时反缩进返回 null", MarkdownEditKt.indentLines("a", 1, 1, true) == null);
        check("空行缩进也返回结果", MarkdownEditKt.indentLines("", 0, 0, false) != null);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
