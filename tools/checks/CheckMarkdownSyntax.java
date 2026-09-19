import com.marknote.app.ui.editor.Heading;
import com.marknote.app.ui.editor.MarkdownSyntaxKt;
import com.marknote.app.ui.editor.TaskBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 「预览保真」四处改写的纯逻辑断言：脚注不吞行、裸链接成链、任务列表标记、标题锚点。
 *
 * 这些函数都在 android-free 的 MarkdownSyntax.kt 里，所以能在这里直接跑 —— 不用模拟器、不用 JUnit。
 * 每条断言背后都是实测过的坑：
 * - `[^1]: 脚注内容` 被 CommonMark 当成链接引用定义，**整行从预览里消失**（真机 dump 确认）
 * - 裸 URL 点了没反应（A/B 对照：标准链接能打开 Chrome）
 * - `- [x] 未完成` 在预览里是字面 `[x]`（Markwon 没装 tasklist 插件）
 *
 * 用法：tools/run_checks.sh
 */
public class CheckMarkdownSyntax {

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

    static void eq(String name, String actual, String expected) {
        if (actual.equals(expected)) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name + "\n      期望: " + expected + "\n      实际: " + actual);
        }
    }

    static String escape(String s) {
        return MarkdownSyntaxKt.keepFootnoteMarkersLiteral(s);
    }

    static String autolink(String s) {
        return MarkdownSyntaxKt.autolinkBareUrls(s);
    }

    static List<TaskBox> boxes(String s) {
        return MarkdownSyntaxKt.taskBoxRanges(s);
    }

    static List<Heading> headings(String... titles) {
        List<Heading> out = new ArrayList<>();
        int offset = 0;
        for (String t : titles) {
            out.add(new Heading(2, t, offset));
            offset += t.length() + 4;
        }
        return out;
    }

    public static void main(String[] args) {
        // ---------------- 脚注：不丢字 ----------------
        eq("脚注引用转义", escape("脚注引用[^1]"), "脚注引用\\[^1]");
        eq("脚注定义行转义（原来整行会消失）", escape("[^1]: 脚注内容"), "\\[^1]: 脚注内容");
        eq("没有脚注的文档原样返回", escape("# 标题\n正文"), "# 标题\n正文");
        eq("行内代码里的方括号不动", escape("`[^1]` 不是脚注"), "`[^1]` 不是脚注");
        eq("围栏代码块里的不动", escape("```\n[^1]: x\n```"), "```\n[^1]: x\n```");
        eq("两个脚注都转义", escape("a[^1]b[^2]"), "a\\[^1]b\\[^2]");

        // ---------------- 裸链接：只包正文里的 http(s) ----------------
        eq("裸链接成链", autolink("见 https://example.com 吧"), "见 <https://example.com> 吧");
        // GFM 的 url autolink 规则是「scheme:// + 域名 + 零个或多个非空格非 < 字符」，
        // 所以紧跟的中日韩文字会被算进链接（GitHub 也是这个行为）。想断开就加个空格。
        eq("紧跟中文时中文算进链接（与 GitHub 一致）",
                autolink("看https://a.cn/x页"), "看<https://a.cn/x页>");
        eq("紧跟空格时断开", autolink("看 https://a.cn/x 页"), "看 <https://a.cn/x> 页");
        eq("中文路径保留（不是截断）",
                autolink("https://zh.wikipedia.org/wiki/中文条目"),
                "<https://zh.wikipedia.org/wiki/中文条目>");
        eq("句末句号不算进 URL", autolink("见 https://a.com."), "见 <https://a.com>.");
        eq("逗号不算进 URL", autolink("a https://a.com, b"), "a <https://a.com>, b");
        eq("配对括号保留", autolink("https://en.wikipedia.org/wiki/Foo_(bar)"),
                "<https://en.wikipedia.org/wiki/Foo_(bar)>");
        eq("不配对的右括号剥掉", autolink("(https://a.com)"), "(<https://a.com>)");
        eq("已有的尖括号写法不重复包", autolink("<https://a.com>"), "<https://a.com>");
        eq("链接目标不动", autolink("[文字](https://a.com)"), "[文字](https://a.com)");
        eq("图片目标不动", autolink("![图](https://a.com/i.png)"), "![图](https://a.com/i.png)");
        eq("行内代码里的 URL 不动", autolink("`https://a.com`"), "`https://a.com`");
        eq("围栏代码块里的 URL 不动", autolink("```\nhttps://a.com\n```"), "```\nhttps://a.com\n```");
        eq("引用定义里的 URL 不动", autolink("[a]: https://a.com"), "[a]: https://a.com");
        eq("http 也认", autolink("http://a.com"), "<http://a.com>");
        eq("非 http 的裸串不包", autolink("www.a.com 和 a.com 都不包"), "www.a.com 和 a.com 都不包");
        // GFM 的尾随标点集合是 `?!.,:*_~`：漏掉 * _ ~ 时，URL 紧邻的强调标记会被吃进链接里
        eq("被 ** 包住的 URL 不被吞", autolink("**https://a.com**"), "**<https://a.com>**");
        eq("被 _ 包住的 URL 不被吞", autolink("_https://a.com_"), "_<https://a.com>_");
        eq("URL 后面的 **加粗** 分开写正常", autolink("https://a.com **粗**"), "<https://a.com> **粗**");
        eq("URL 中间的 _ 保留", autolink("https://a.com/a_b"), "<https://a.com/a_b>");
        eq("波浪号围栏里的 URL 不动", autolink("~~~\nhttps://a.com\n~~~"), "~~~\nhttps://a.com\n~~~");
        eq("HTML 标签里的 URL 不动",
                autolink("<a href=\"https://a.com\">x</a>"), "<a href=\"https://a.com\">x</a>");
        eq("表格单元格里的 URL 成链", autolink("| https://a.com |"), "| <https://a.com> |");

        // ---------------- 任务列表标记（按渲染后文本找） ----------------
        List<TaskBox> b = boxes("[ ] 未完成\n[x] 已完成\n[X] 大写也算");
        check("找到 3 个任务标记", b.size() == 3);
        check("第一个未勾选且区间正确",
                b.size() == 3 && !b.get(0).getChecked() && b.get(0).getStart() == 0 && b.get(0).getEnd() == 3);
        check("第二个勾选", b.size() == 3 && b.get(1).getChecked());
        check("大写 X 也算勾选", b.size() == 3 && b.get(2).getChecked());
        List<TaskBox> indented = boxes("  [x] 缩进子项");
        check("缩进子项：区间跳过前导空格",
                indented.size() == 1 && indented.get(0).getStart() == 2 && indented.get(0).getEnd() == 5);
        check("行中出现的不算", boxes("看到 [x] 了吗").isEmpty());
        check("括号后没有空白不算任务项", boxes("[x]紧贴").isEmpty());
        check("普通正文没有标记", boxes("正文\n\n# 标题").isEmpty());

        // ---------------- 标题锚点 ----------------
        eq("中文标题的 slug", MarkdownSyntaxKt.anchorSlug("更新余地（规划）"), "更新余地规划");
        eq("英文标题的 slug", MarkdownSyntaxKt.anchorSlug("Hello World"), "hello-world");
        eq("下划线与连字符保留", MarkdownSyntaxKt.anchorSlug("A_B-C"), "a_b-c");
        eq("行内标记在 slug 里去掉", MarkdownSyntaxKt.anchorSlug("**加粗**标题"), "加粗标题");

        List<Heading> hs = headings("安装步骤", "更新余地（规划）", "**加粗**标题");
        check("按中文 slug 命中", MarkdownSyntaxKt.findHeadingByAnchor(hs, "#安装步骤") != null
                && MarkdownSyntaxKt.findHeadingByAnchor(hs, "#安装步骤").getTitle().equals("安装步骤"));
        check("带括号的标题：slug 命中",
                MarkdownSyntaxKt.findHeadingByAnchor(hs, "#更新余地规划") != null);
        check("URL 编码的锚点也认",
                MarkdownSyntaxKt.findHeadingByAnchor(hs,
                        "#%E5%AE%89%E8%A3%85%E6%AD%A5%E9%AA%A4") != null);
        check("重名后缀 -2 忽略后命中",
                MarkdownSyntaxKt.findHeadingByAnchor(hs, "#安装步骤-2") != null);
        check("标题带行内标记时按渲染文字命中",
                MarkdownSyntaxKt.findHeadingByAnchor(hs, "#加粗标题") != null);
        check("大小写不敏感",
                MarkdownSyntaxKt.findHeadingByAnchor(headings("Hello World"), "#hello-world") != null);
        check("找不到的锚点返回 null",
                MarkdownSyntaxKt.findHeadingByAnchor(hs, "#不存在的标题") == null);
        check("空锚点返回 null", MarkdownSyntaxKt.findHeadingByAnchor(hs, "#") == null);

        // ---------------- 组合入口 ----------------
        eq("preparePreviewMarkdown 两处都生效",
                MarkdownSyntaxKt.preparePreviewMarkdown("[^1]: 见 https://a.com"),
                "\\[^1]: 见 <https://a.com>");

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
