import com.marknote.app.ui.editor.EditorNotice;
import com.marknote.app.ui.editor.EditorNoticeStateKt;

/**
 * 编辑器顶部提示的优先级断言。
 *
 * 这段逻辑只有三个布尔输入，但**八种组合里每一种都可能真实出现**：MediaStore 来源的文件
 * 既只读、又拿不到持久授权，保存失败之后三条全中。顺序弄反了不会报错，只会让用户看到
 * 一条与当前问题无关的提示（比如只读文件显示「退出应用后打不开」），然后照它去重新授权。
 *
 * 「当前这个文件」是否真的做过外部打开 / 文件夹授权，由设备验证覆盖；这里只管
 * 「给定三个标记，该报哪一条」。
 *
 * 用法：tools/run_checks.sh
 */
public class CheckEditorNotice {

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

    /** 把期望写成字符串，失败时看得懂 */
    static String show(EditorNotice notice) {
        return notice == null ? "（不报）" : notice.name();
    }

    public static void main(String[] args) {
        // ---------------- 八种组合全枚举 ----------------
        // {saveFailed, readOnly, hasPersistedAccess, 期望}
        Object[][] cases = {
                {false, false, true, null},                                  // 一切正常
                {false, false, false, EditorNotice.NO_PERSISTED_ACCESS},      // 可写但没有持久授权
                {false, true, true, EditorNotice.READ_ONLY},
                {false, true, false, EditorNotice.READ_ONLY},
                {true, false, true, EditorNotice.SAVE_FAILED},
                {true, false, false, EditorNotice.SAVE_FAILED},
                {true, true, true, EditorNotice.SAVE_FAILED},
                {true, true, false, EditorNotice.SAVE_FAILED},                // 三条全中
        };
        for (Object[] c : cases) {
            boolean saveFailed = (Boolean) c[0];
            boolean readOnly = (Boolean) c[1];
            boolean persisted = (Boolean) c[2];
            EditorNotice want = (EditorNotice) c[3];
            EditorNotice got = EditorNoticeStateKt.editorNoticeOf(saveFailed, readOnly, persisted);
            check(String.format("saveFailed=%s readOnly=%s hasPersistedAccess=%s → %s（实得 %s）",
                            saveFailed, readOnly, persisted, show(want), show(got)),
                    got == want);
        }

        // ---------------- 优先级单独再钉一遍 ----------------
        // 全枚举已经覆盖了这些，但单列出来是为了让「顺序被改反」这件事一眼可读：
        // 反了之后红的会是这几条里最直观的那一两条。
        check("保存失败压过只读与无授权",
                EditorNoticeStateKt.editorNoticeOf(true, true, false) == EditorNotice.SAVE_FAILED);
        check("只读压过无授权",
                EditorNoticeStateKt.editorNoticeOf(false, true, false) == EditorNotice.READ_ONLY);
        check("只有「没有持久授权」才报最后那条",
                EditorNoticeStateKt.editorNoticeOf(false, false, false)
                        == EditorNotice.NO_PERSISTED_ACCESS);
        check("有持久授权、又不只读、也没保存失败 → 一条都不报",
                EditorNoticeStateKt.editorNoticeOf(false, false, true) == null);
        check("三条全中时只报最重的那一条",
                EditorNoticeStateKt.editorNoticeOf(true, true, false) == EditorNotice.SAVE_FAILED);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
