import com.marknote.app.ui.editor.MarkdownSyntaxKt;

/**
 * 大纲跳转用的两个纯函数（plainTitle / renderedOffsetOfHeading）的断言，不需要模拟器。
 *
 * 守的是一条：**预览里从大纲跳标题，必须正好落在标题那一行**。
 *
 * 渲染后 Markdown 语法会消失（`# ` 前缀、`**加粗**` 的星号、链接语法、
 * 以及行内公式的 `$$`），所以源码偏移不能直接拿去滚动。renderedOffsetOfHeading
 * 的做法是「按长度比例估一个位置，再在渲染文本里找离它最近的标题文字」——
 * 于是**只要 plainTitle 算出来的标题跟渲染文本对不上，indexOf 就落空，
 * 跳转会静默退化成「大概位置」**（不报错、不崩，只是没对准）。
 *
 * 行内公式是新增的一种「渲染后源码会消失」的东西（`$$E = mc^2$$` 渲染成 `E = mc^2`，
 * 分隔符被剥掉），这个文件就是防它把标题文字算歪。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckHeadingOffset {

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

    static String plainTitle(String s) {
        return MarkdownSyntaxKt.plainTitle(s);
    }

    /** 源码里的标题 → 渲染后应当出现的文字；断言返回的偏移正好指在这段文字上 */
    static void jumpLandsOnHeading(String name, String source, String rendered, String marker, String title) {
        int headingOffset = source.indexOf(marker);
        if (headingOffset < 0) {
            check(name + "（用例自身写错了：源码里没有 " + marker + "）", false);
            return;
        }
        int offset = MarkdownSyntaxKt.renderedOffsetOfHeading(headingOffset, source, rendered);
        boolean inRange = offset >= 0 && offset <= rendered.length();
        check(name + "：偏移在合法范围内", inRange);
        if (inRange) {
            check(name + "：跳转落在标题上", rendered.startsWith(title, offset));
        }
    }

    // ---------- plainTitle：源码标题 → 渲染后的标题 ----------

    static void plainTitleBasics() {
        check("裸标题不变", plainTitle("标题").equals("标题"));
        check("去掉加粗星号", plainTitle("**加粗**标题").equals("加粗标题"));
        check("链接只留文字", plainTitle("[文字](https://example.com)标题").equals("文字标题"));
        check("行内代码去反引号", plainTitle("`代码`标题").equals("代码标题"));
        check("删除线去波浪号", plainTitle("~~删除~~标题").equals("删除标题"));
        check("首尾空白被去掉", plainTitle("  标题  ").equals("标题"));
    }

    static void plainTitleMath() {
        check("行内公式去掉 $$ 只留公式源",
                plainTitle("关于 $$E = mc^2$$ 的推导").equals("关于 E = mc^2 的推导"));
        check("公式里的 * 不能被当成斜体标记吃掉",
                plainTitle("$$a*b$$ 与 *斜体*").equals("a*b 与 斜体"));
        check("公式里的反引号同样要保住",
                plainTitle("$$a`b$$").equals("a`b"));
        check("公式里的下划线也要保住（别被当成斜体/加粗标记）",
                plainTitle("$$x_1 + x_2$$").equals("x_1 + x_2"));
        check("未闭合的 $$ 原样留下（找不到就退回估算，不崩）",
                plainTitle("$$没闭合").equals("$$没闭合"));
    }

    // ---------- renderedOffsetOfHeading：整条跳转链路 ----------

    static void jumpBasics() {
        String source = "# 标题一\n\n正文若干\n\n## 标题二\n";
        String rendered = "标题一\n正文若干\n标题二\n";
        jumpLandsOnHeading("无标记的普通标题", source, rendered, "## 标题二", "标题二");
    }

    static void jumpAfterFormulas() {
        String source =
                "# 标题一\n\n"
                + "行内 $$E = mc^2$$ 与 $$a^2 + b^2 = c^2$$ 两处公式。\n\n"
                + "## 标题二\n";
        String rendered =
                "标题一\n"
                + "行内 E = mc^2 与 a^2 + b^2 = c^2 两处公式。\n"
                + "标题二\n";
        jumpLandsOnHeading("标题出现在一堆公式之后", source, rendered, "## 标题二", "标题二");
    }

    static void jumpToHeadingWithFormula() {
        String source =
                "# 标题一\n\n"
                + "这里先垫一段足够长的正文，好让按长度比例估算出来的位置明显偏离标题本身。\n\n"
                + "## 关于 $$E = mc^2$$ 的推导\n";
        String rendered =
                "标题一\n"
                + "这里先垫一段足够长的正文，好让按长度比例估算出来的位置明显偏离标题本身。\n"
                + "关于 E = mc^2 的推导\n";
        jumpLandsOnHeading("标题本身含公式", source, rendered, "## 关于", "关于 E = mc^2 的推导");
    }

    static void jumpToHeadingWithTrickyFormula() {
        String source =
                "# 标题一\n\n"
                + "垫一段正文，让估算偏掉。\n\n"
                + "## $$a*b$$ 与 *斜体*\n";
        String rendered =
                "标题一\n"
                + "垫一段正文，让估算偏掉。\n"
                + "a*b 与 斜体\n";
        jumpLandsOnHeading("标题里的公式含 * 号", source, rendered, "## $$a*b$$", "a*b 与 斜体");
    }

    static void jumpDegradesSafely() {
        // 标题里带未闭合的 $$：plainTitle 算出来的文字跟渲染文本对不上，
        // 于是退回「按比例估算」。这是可接受的降级——不崩、不越界。
        String source = "# 标题一\n\n垫一段正文。\n\n## $$没闭合\n";
        String rendered = "标题一\n垫一段正文。\n$$没闭合\n";
        int headingOffset = source.indexOf("## $$没闭合");
        int offset = MarkdownSyntaxKt.renderedOffsetOfHeading(headingOffset, source, rendered);
        check("未闭合公式：退回估算且不越界", offset >= 0 && offset <= rendered.length());
    }

    public static void main(String[] args) {
        plainTitleBasics();
        plainTitleMath();
        jumpBasics();
        jumpAfterFormulas();
        jumpToHeadingWithFormula();
        jumpToHeadingWithTrickyFormula();
        jumpDegradesSafely();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
