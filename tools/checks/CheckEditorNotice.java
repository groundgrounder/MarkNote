import com.marknote.app.ui.editor.EditorNotice;
import com.marknote.app.ui.editor.EditorNoticeStateKt;

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

    static String show(EditorNotice notice) {
        return notice == null ? "（不报）" : notice.name();
    }

    public static void main(String[] args) {

        Object[][] cases = {
                {false, false, true, null},
                {false, false, false, EditorNotice.NO_PERSISTED_ACCESS},
                {false, true, true, EditorNotice.READ_ONLY},
                {false, true, false, EditorNotice.READ_ONLY},
                {true, false, true, EditorNotice.SAVE_FAILED},
                {true, false, false, EditorNotice.SAVE_FAILED},
                {true, true, true, EditorNotice.SAVE_FAILED},
                {true, true, false, EditorNotice.SAVE_FAILED},
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
