import com.marknote.app.data.DocumentRepositoryKt;

import java.util.List;

/**
 * 最近列表「坏数据抢救」的纯逻辑断言：从解析不了的 JSON 里抠出还完整的条目子串。
 *
 * 测的是 DocumentRepository.kt 末尾的顶层函数 salvageEntryJson。之所以是顶层函数而不是
 * 类里的 private，就是为了能在这里跑：那一步之后要交给 org.json 解析，而 org.json 只在
 * Android 里有，JVM 断言加载不了 —— 卡在中间这一层纯字符串扫描上，正好把「能救几条」
 * 这个最需要证据的问题钉住。
 *
 * **为什么值得单独钉**：这里的失败方式很安静。抢救逻辑若写错（正则贪婪、或按「全坏」处理），
 * 用户的最近列表会从「丢掉最后一条」变成「整份清空」，而代码不会报任何错。
 * 下面每一条都同时给出「该救回来的」和「该跳过的」两类用例。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckRecentsSalvage {

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

    static String joined(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s);
        }
        return sb.toString();
    }

    static void checkCount(String label, String raw, int expected) {
        List<String> got = DocumentRepositoryKt.salvageEntryJson(raw);
        check(label + " → " + got.size() + " 段（期望 " + expected + "）", got.size() == expected);
    }

    public static void main(String[] args) {
        final String a = "{\"uri\":\"a\",\"name\":\"A.md\",\"time\":1,\"tree\":\"\"}";
        final String b = "{\"uri\":\"b\",\"name\":\"B.md\",\"time\":2,\"tree\":\"t\"}";

        // ---------------- 正常情况：整份 JSON 好的时候走不到这里，但不该误伤 ----------------
        List<String> two = DocumentRepositoryKt.salvageEntryJson("[" + a + "," + b + "]");
        checkCount("两条完整", "[" + a + "," + b + "]", 2);
        check("抠出来的是原样的对象（一个字符都没改）", two.get(0).equals(a));
        check("顺序保持加入顺序", two.get(1).equals(b));

        // ---------------- 核心场景：尾部截断（写入被中断） ----------------
        // 这是实际最可能遇到的坏法：前面整整齐齐，最后一两条被切断。
        checkCount("尾部截断在半条中间", "[" + a + ",{\"uri\":\"b\",\"na", 1);
        check("截断时救回来的是完整的那条",
                DocumentRepositoryKt.salvageEntryJson("[" + a + ",{\"uri\":\"b\",\"na").get(0).equals(a));
        checkCount("三条完整 + 半条", a + "," + b + ",{\"uri\":\"c\"},{\"uri\":\"d", 3);
        checkCount("半条在开头（前面被切）", "\"name\":\"A.md\"}," + b, 1);

        // ---------------- 该跳过的：名字里带花括号会让那一条抠不完整 ----------------
        // 这不是缺陷，是「宁可少救一条，也不能救出错的」：子串交给 JSONObject 会解析失败，
        // 调用方（salvageEntries）用 runCatching 跳过它。这里如实钉住这个形状。
        String braceName = "{\"uri\":\"x\",\"name\":\"a}b.md\",\"time\":1,\"tree\":\"\"}";
        List<String> brace = DocumentRepositoryKt.salvageEntryJson(braceName);
        checkCount("名字里带花括号 → 抠出一段（但不完整）", braceName, 1);
        check("那一段确实是不完整的（少了尾巴），调用方会跳过它",
                brace.get(0).equals("{\"uri\":\"x\",\"name\":\"a}"));
        checkCount("坏的那条不影响它后面正常的条目",
                braceName + "," + b, 2);

        // ---------------- 什么都没有的情况 ----------------
        checkCount("不是 JSON 的串", "not json at all", 0);
        checkCount("空串", "", 0);
        checkCount("JSON 数组壳但里面没东西", "[]", 0);
        checkCount("只有标量", "12345", 0);

        // 空对象算一段（它本身是完整的）—— 是否采用由调用方按 uri 是否为空决定
        checkCount("空对象本身是完整的", "{}", 1);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
