// Shizuku 特权 UserService 接口 —— M1.1（方案 §1.1/§六；D63 操作层）。
// 跑在 Shizuku 服务进程（ADB 激活=shell uid 2000 / root=uid 0），实现类 SystemActionUserService。
// destroy() = Shizuku 销毁约定事务码（unbindUserService(remove=true) 时由 server 调用）。
package com.zero.v2.services.shizuku;

interface ISystemActionService {

    void destroy() = 16777114; // Destroy method defined by Shizuku server

    String runShell(String cmd) = 1;      // shell-uid 执行（input/am/pm/settings/content），返回 "exit=N\n输出"

    void tap(int x, int y) = 2;

    void swipe(int x1, int y1, int x2, int y2, int ms) = 3;

    void inputText(String text) = 4;      // 空格须转 %s（Android input text 约定）

    void pressKey(String name) = 5;       // KEYCODE_* 名或数字

    boolean startActivity(String component, String action, String data) = 6; // am start -n/-a/-d

    boolean grantPermission(String pkg, String perm) = 7;                    // pm grant

    boolean setSetting(String namespace, String key, String value) = 8;      // settings put
}
