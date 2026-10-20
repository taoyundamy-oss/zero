# modify_reminder回显成功但remark未落盘：公开合成复现

原实机对话包含个人日程，未公开。此处只保留工程原因与合成验收。

问题：修改提醒备注时，函数返回的新文本未在数据库持久化，随后list_reminders仍显示旧值。
原因：SystemStateDao.updateSchedule的UPDATE语句遗漏remark列；回显使用输入参数，掩盖了写入遗漏。
修正：补齐remark写入，修改、周期重排和missed重排三类调用方均明确传值，避免重排覆盖备注。
合成回归：创建备注old-text-alpha，修改为new-text-beta；同时核对数据库remark和list_reminders返回值均为new-text-beta，再重启重读。原始账户、考试、地址和手机号材料不随仓库分发。
