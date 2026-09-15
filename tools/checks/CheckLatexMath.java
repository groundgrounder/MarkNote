import com.marknote.app.ui.editor.EditorScreenKt;
import com.marknote.app.ui.editor.LatexMathKt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单 `$` 行内公式的边界规则断言（纯逻辑，不需要模拟器）。
 *
 * 守的两条：
 * 1. **该认的要认**：$\\alpha$、$x^2 + y^2$ 这类要被识别成公式；
 * 2. **不该认的别认**：「$5 到 $10」这种金额、`$ x$`、`$x $`、`$x$1` 都不能被吃掉——
 *    误判的代价是用户正文里的字符消失，比「公式没渲染」严重得多。
 *
 * 还有一条守的是大纲跳转：标题里带单 `$` 公式时，plainTitle 必须跟渲染文本一样
 * 把分隔符剥掉，否则 indexOf 落空、跳转静默退化成「大概位置」。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckLatexMath {

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

    static void eq(String name, Object actual, Object expected) {
        boolean ok = actual == null ? expected == null : actual.equals(expected);
        if (!ok) {
            System.out.println("      期望 " + expected + "，实际 " + actual);
        }
        check(name, ok);
    }

    // ---------- 从某个位置开始能否匹配（处理器的 matchAt 语义） ----------

    static boolean matchesAtStart(String s) {
        // lookingAt() == Kotlin 的 Regex.matchAt(s, 0)：必须从当前位置开始算匹配，
        // 不能像 find() 那样允许往后跳（那正是 ext-latex 自带处理器的毛病）
        return LatexMathKt.getInlineMathPattern().toPattern().matcher(s).lookingAt();
    }

    // ---------- 整段里被识别为公式的内容（验证金额不被误判） ----------

    static List<String> formulasIn(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = LatexMathKt.getInlineMathPattern().toPattern().matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static void inlineMathRecognised() {
        check("$\\alpha$ 被识别", matchesAtStart("$\\alpha$"));
        check("$x^2 + y^2$ 被识别", matchesAtStart("$x^2 + y^2$"));
        check("单字符 $x$ 被识别", matchesAtStart("$x$"));
        check("含中文的 $公式$ 也照样识别（内容不做限制）", matchesAtStart("$公式$"));
        check("闭 $ 后接中文标点仍识别", matchesAtStart("$x$，"));

        eq("$\\alpha$ 的公式源是 \\alpha", formulasIn("$\\alpha$").toString(), "[\\alpha]");
        eq("一行里两处都能识别", formulasIn("这里是 $\\alpha$ 和 $\\beta$ 两个符号").toString(),
                "[\\alpha, \\beta]");
        eq("公式里可以有空格", formulasIn("$a b$").toString(), "[a b]");
        eq("公式后面紧跟中文标点仍识别", formulasIn("$x$，以及 $y$。").toString(), "[x, y]");
        // 两个单美元公式紧贴属于畸形写法（真实文档不会这么写）。
        // 这个位置与「金额紧挨 $$ 公式」同构，规则上选了后者，所以这里**记录**实际行为：
        // 前者让位、后者照常识别，且不吞字符。断言这条是为了别让规则被无声改掉。
        eq("紧贴的两个单美元公式：前者让位（畸形输入，有意取舍）",
                formulasIn("$x$$y$").toString(), "[y]");
    }

    static void inlineMathRejected() {
        check("开 $ 后紧跟空白 → 不认", !matchesAtStart("$ x$"));
        check("闭 $ 前是空白 → 不认", !matchesAtStart("$x $"));
        check("闭 $ 后紧跟数字 → 不认", !matchesAtStart("$x$1"));
        check("内容为空（$$）→ 不认", !matchesAtStart("$$"));
        check("没有闭合 $ → 不认", !matchesAtStart("$x"));
        check("跨行 → 不认", !matchesAtStart("$x\ny$"));
        check("单独的 $ → 不认", !matchesAtStart("$"));

        eq("「$5 到 $10」是金额不是公式", formulasIn("$5 到 $10").toString(), "[]");
        eq("「$10 和 $20 之间」同上", formulasIn("$10 和 $20 之间").toString(), "[]");
        eq("价格句子里夹的两处美元不被识别",
                formulasIn("价格是 $5，另一个是 $10 元").toString(), "[]");
        eq("三个金额连写也不误判",
                formulasIn("$10 到 $20 再到 $30").toString(), "[]");
        // 闭 $ 落在后面那个 $$ 的第一个字符上：不挡的话会切出一个内容为「5，」的假公式，
        // 顺带把真正的 $$…$$ 拆坏
        eq("紧挨着 $$ 的金额不被误判",
                formulasIn("价格 $5，$$E=mc^2$$ 这一行").toString(), "[]");
    }

    // ---------- 大纲跳转：标题里的单 $ 公式 ----------

    static String plainTitle(String s) {
        return EditorScreenKt.plainTitle(s);
    }

    static void plainTitleSingleDollar() {
        eq("标题里的单 $ 公式只留公式源",
                plainTitle("关于 $\\alpha$ 的推导"), "关于 \\alpha 的推导");
        eq("两种写法混在一行",
                plainTitle("$x_1$ 与 $$y_2$$"), "x_1 与 y_2");
        eq("公式里的 * 不能被斜体规则吃掉",
                plainTitle("$a*b$ 与 *斜体*"), "a*b 与 斜体");
        eq("公式里的反引号要保住", plainTitle("$a`b$"), "a`b");
        eq("金额不能被当成公式剥掉",
                plainTitle("价格 $5 到 $10"), "价格 $5 到 $10");
        eq("未闭合的单 $ 原样留下", plainTitle("$没闭合"), "$没闭合");
    }

    static void jumpLandsOnTitleWithSingleDollar() {
        String source = "# 标题一\n\n垫一段够长的正文文字，让按长度比例估算出来的位置偏掉。\n\n"
                + "## 关于 $\\alpha$ 的推导\n";
        String rendered = "标题一\n垫一段够长的正文文字，让按长度比例估算出来的位置偏掉。\n"
                + "关于 \\alpha 的推导\n";
        int headingOffset = source.indexOf("## 关于");
        int offset = EditorScreenKt.renderedOffsetOfHeading(headingOffset, source, rendered);
        check("带单 $ 公式的标题：偏移在合法范围内", offset >= 0 && offset <= rendered.length());
        check("带单 $ 公式的标题：跳转正好落在标题上",
                rendered.startsWith("关于 \\alpha 的推导", offset));
    }

    public static void main(String[] args) {
        inlineMathRecognised();
        inlineMathRejected();
        plainTitleSingleDollar();
        jumpLandsOnTitleWithSingleDollar();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
