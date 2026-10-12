import com.marknote.app.data.DocumentRepositoryKt;

import java.util.List;

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

        List<String> two = DocumentRepositoryKt.salvageEntryJson("[" + a + "," + b + "]");
        checkCount("两条完整", "[" + a + "," + b + "]", 2);
        check("抠出来的是原样的对象（一个字符都没改）", two.get(0).equals(a));
        check("顺序保持加入顺序", two.get(1).equals(b));

        checkCount("尾部截断在半条中间", "[" + a + ",{\"uri\":\"b\",\"na", 1);
        check("截断时救回来的是完整的那条",
                DocumentRepositoryKt.salvageEntryJson("[" + a + ",{\"uri\":\"b\",\"na").get(0).equals(a));
        checkCount("三条完整 + 半条", a + "," + b + ",{\"uri\":\"c\"},{\"uri\":\"d", 3);
        checkCount("半条在开头（前面被切）", "\"name\":\"A.md\"}," + b, 1);

        String braceName = "{\"uri\":\"x\",\"name\":\"a}b.md\",\"time\":1,\"tree\":\"\"}";
        List<String> brace = DocumentRepositoryKt.salvageEntryJson(braceName);
        checkCount("名字里带花括号 → 抠出一段（但不完整）", braceName, 1);
        check("那一段确实是不完整的（少了尾巴），调用方会跳过它",
                brace.get(0).equals("{\"uri\":\"x\",\"name\":\"a}"));
        checkCount("坏的那条不影响它后面正常的条目",
                braceName + "," + b, 2);

        checkCount("不是 JSON 的串", "not json at all", 0);
        checkCount("空串", "", 0);
        checkCount("JSON 数组壳但里面没东西", "[]", 0);
        checkCount("只有标量", "12345", 0);

        checkCount("空对象本身是完整的", "{}", 1);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
