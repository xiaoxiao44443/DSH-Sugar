package app.dsh.sugar

import app.dsh.sugar.runtime.MobileUseTasks.NoticeTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPetActivityTest {
    @Test fun companionFinishesReplyWithoutTimingItsOpenScreenAndRestartsOnWake() {
        val task = NoticeTask("phone", "main", null, false, "chat", caption = "查看画面", captionAt = 12)
        val reply = DesktopPetActivity.Result("chat", "say-99", "reply", "这张图里有两个角色。", atMicros = 20)
        val status = DesktopPetActivity.Status("thinking", "chat", caption = "读取画面", captionAt = 10, reply = reply)
        val held = task.copy(awaitingCompanion = true)
        fun render(task: NoticeTask, state: DesktopPetActivity.Status = status) = state.display(listOf(task))
        assertEquals(reply, render(held).result)
        assertEquals("awaitingCompanion", render(held).phoneStep)
        assertNull(render(held.copy(captionAt = 22)).caption)
        assertEquals(reply, render(held.copy(captionAt = 22)).result)
        assertNull(render(held, status.copy(reply = null)).progressTask)
        assertNull(render(held, status.copy(sessionId = "other", reply = null)).progressTask)

        val resumed = task.copy(resumedAt = 30)
        assertNull(render(resumed).result)
        assertNull(render(resumed).caption)
        assertEquals(resumed, render(resumed).progressTask)
        assertEquals("确认菜单", render(resumed.copy(caption = "确认菜单", captionAt = 31)).caption)
        val clock = PhoneCaptionProgress()
        fun tick(task: NoticeTask, now: Long) = clock.update(render(task, status.copy(reply = null)).progressTask,
            "看画面", true, false, now)
        assertEquals(15_000L, tick(task, 1000).nextIn)
        assertEquals(15L, tick(task, 16_000).hint?.seconds)
        assertNull(tick(held, 16_050).nextIn)
        assertNull(tick(held, 120_000).hint)
        assertEquals(15_000L, tick(resumed, 120_100).nextIn)
        assertEquals(15_000L, tick(resumed.copy(resumedAt = 40), 145_000).nextIn)
    }

    @Test fun slowPhoneStepPreservesCaptionAcrossWaitPollsAndUsesOnlyObservedActivity() {
        val progress = PhoneCaptionProgress()
        val task = NoticeTask("screen-a", "main", null, false, "chat")
        val caption = "先核对金额，确认后再提交"
        fun at(time: Long, phone: NoticeTask? = task, text: String? = caption, thinking: Boolean = false) =
            progress.update(phone, text, false, thinking, time)
        assertEquals(15_000L, at(1000L).nextIn)
        assertEquals(1L, at(15_999L, task.copy(waiting = true)).nextIn)
        assertEquals(PhoneCaptionProgress.Hint(PhoneCaptionProgress.Activity.WAITING, 15L),
            at(16_000L, task.copy(waiting = true)).hint)
        assertEquals(4000L, at(17_000L).nextIn)
        assertEquals(PhoneCaptionProgress.Activity.STEP, at(18_000L, task.copy(lastObservation = 100L)).hint?.activity)
        val measured = task.copy(lastObservation = 12_000L, lastDispatch = 15_000L)
        assertEquals(PhoneCaptionProgress.Activity.DISPATCHED, at(19_000L, measured).hint?.activity)
        assertEquals(PhoneCaptionProgress.Activity.OBSERVED, at(20_000L, measured.copy(lastObservation = 19_900L)).hint?.activity)
        assertEquals(PhoneCaptionProgress.Activity.THINKING, at(21_000L, measured, thinking = true).hint?.activity)
        assertNull(at(22_000L, task, "另一个步骤").hint)
        assertNull(at(40_000L, task.copy(id = "screen-b"), "另一个步骤").hint)
        assertNull(at(60_000L, null).nextIn)
        assertNull(at(61_000L).hint)
        assertNull(at(80_000L, task, "").nextIn)
    }

    @Test fun authoredStageShowsElapsedOnlyEvenAsPhoneActivityChanges() {
        val progress = PhoneCaptionProgress()
        val task = NoticeTask("phone", "main", null, false, "chat", caption = "核对金额后再提交")
        fun update(phone: NoticeTask, now: Long, thinking: Boolean = false) =
            progress.update(phone, phone.caption, true, thinking, now).hint
        assertNull(update(task, 1000))
        assertNull(update(task.copy(waiting = true), 15_999))
        val first = PhoneCaptionProgress.Hint(PhoneCaptionProgress.Activity.STEP, 15)
        assertEquals(first, update(task.copy(waiting = true), 16_000))
        val second = PhoneCaptionProgress.Hint(PhoneCaptionProgress.Activity.STEP, 20)
        assertEquals(second, update(task.copy(lastObservation = 20_800), 21_000, thinking = true))
        assertEquals(second, update(task.copy(lastDispatch = 21_100), 21_300))
        assertNull(update(task.copy(caption = "金额一致，提交报销"), 21_500))
    }

    @Test fun phoneStatusAndToolDescriptionsFollowOriginalOrderInTheSameConversation() {
        val shop = NoticeTask("game-task", "main", null, false, "game", "在商店找花火，给阵容补输出", captionAt = 20)
        fun render(text: String?, at: Long, task: NoticeTask = shop, phase: String = "10", hint: String? = null) =
            DesktopPetActivity.Status("working", "game", phase, caption = text, phoneStep = hint, captionAt = at)
                .display(listOf(task))
        val waiting = "等待 2-6 战斗结算，再决定是否升级阵容"
        assertEquals(shop.caption, render("查看商店", 10).caption)
        assertEquals(waiting, render(waiting, 30).caption)
        val moreSpecific = shop.copy(caption = "看到挑战成功了，等结算数字出现", captionAt = 31)
        assertEquals(moreSpecific.caption, render(waiting, 30, moreSpecific, "11", "screen").caption)
        assertEquals(moreSpecific.caption, render(waiting, 30, moreSpecific, "12").caption)
        assertEquals(waiting, render(waiting, 32, moreSpecific).caption)
        val cleared = moreSpecific.copy(caption = "", captionAt = 33)
        assertNull(render(waiting, 32, cleared).caption)
        assertEquals(waiting, render(waiting, 34, cleared).caption)
        assertEquals("error", render(null, 35, cleared, hint = "error").phoneStep)
        assertEquals("screen", render(null, 35, cleared, hint = "screen").phoneStep)
        val progress = PhoneCaptionProgress()
        assertNull(progress.update(shop, waiting, true, false, 0, 30).hint)
        assertEquals(15, progress.update(shop, waiting, true, false, 15_000, 30).hint?.seconds?.toInt())
        assertNull(progress.update(shop, waiting, true, false, 15_001, 32).hint)
    }

    @Test fun explicitPhoneStatusSupersedesOnlyOlderSpeechFromItsConversation() {
        val speech = DesktopPetActivity.Result("chat", "say-1", "reply", "计算器已打开", atMicros = 20)
        val earlier = NoticeTask("phone", "main", null, false, "chat", caption = "打开计算器", captionAt = 10)
        val latest = earlier.copy(caption = "核对算式后继续观察", captionAt = 30)
        val status = DesktopPetActivity.Status("thinking", "chat", reply = speech)
        assertEquals(speech, status.display(listOf(earlier)).result)
        val updated = status.display(listOf(latest))
        assertNull(updated.result)
        assertEquals(latest.caption, updated.caption)
        assertEquals(latest, updated.progressTask)
        assertFalse(speech.supersededBy(latest.copy(conversation = "other")))
        val laterTool = status.copy(caption = "查看最终结果", captionAt = 40).display(listOf(latest))
        assertNull(laterTool.result)
        assertEquals("查看最终结果", laterTool.caption)
        assertNull(status.display(listOf(latest.copy(caption = ""))).result)
        val newSpeech = speech.copy(id = "say-2", atMicros = 31)
        assertEquals(newSpeech, status.copy(reply = newSpeech).display(listOf(latest)).result)
        assertEquals(speech, status.display(emptyList()).result)
    }

    @Test fun completionSurvivesIdleAndRetriesWithoutReopeningAfterDismiss() {
        val store = DesktopPetActivity
        val owner = Any()
        val other = Any()
        val tool = DesktopPetActivity.Result("chat-a", "tool-4", "operation", "Saved")
        val answer = DesktopPetActivity.Result("chat-a", "turn-6", "answer", "Ready")
        store.attach(owner)
        try {
            store.publish(owner, "working", "chat-a", tool, "1")
            assertEquals(tool, store.status().visibleResult("working", "chat-a"))
            assertNull(store.status().visibleResult("phone", "chat-a"))
            store.publish(owner, "thinking", "chat-a", tool, "2")
            assertNull(store.status().visibleResult("thinking", "chat-a"))
            assertNull(store.status().operation)
            store.publish(owner, "idle", null, answer)
            store.publish(owner, "idle", null, null)
            assertEquals(answer, store.status().result)
            store.publish(owner, "thinking", "chat-b", null)
            assertNull(store.status().visibleResult("thinking", "chat-b"))
            assertEquals("chat-b", store.status().sessionId)
            store.dismiss(tool)
            assertEquals(answer, store.status().result)
            store.attach(other)
            store.publish(owner, "working", "chat-a", tool)
            assertEquals("idle", store.status().state)
            assertEquals(answer, store.status().result)
            store.dismiss(answer)
            store.publish(other, "idle", null, answer)
            assertNull(store.status().result)
        } finally {
            store.dismiss(answer)
            store.dismiss(tool)
            store.detach(other)
            store.detach(owner)
        }
    }

    @Test fun stepResultsExpireWithoutLosingAnotherChatsFinalAnswer() {
        val store = DesktopPetActivity
        val owner = Any()
        val saved = DesktopPetActivity.Result("writes", "tool-8", "operation", "Saved")
        val answer = DesktopPetActivity.Result("reads", "turn-9", "answer", "Ready")
        val failed = DesktopPetActivity.Result("writes", "tool-12", "error", null)
        val generic = DesktopPetActivity.Result("writes", "tool-13", "operation", null)
        store.attach(owner)
        try {
            store.publish(owner, "coding", "writes", saved, "7")
            store.publish(owner, "coding", "writes", answer, "7")
            assertEquals(saved, store.status().visibleResult("coding", "writes"))
            store.publish(owner, "coding", "writes", null, "10")
            assertNull(store.status().visibleResult("coding", "writes"))
            store.publish(owner, "working", "writes", failed, "11")
            assertEquals(failed, store.status().visibleResult("working", "writes"))
            store.dismiss(failed)
            store.publish(owner, "working", "writes", failed, "11")
            assertNull(store.status().visibleResult("working", "writes"))
            store.publish(owner, "asking", "writes", generic, "q")
            assertNull(store.status().visibleResult("asking", "writes"))
            store.publish(owner, "idle", null, null)
            assertEquals(answer, store.status().visibleResult("idle", null))
            store.dismiss(answer)
            assertNull(store.status().visibleResult("idle", null))
        } finally {
            store.dismiss(answer)
            store.detach(owner)
        }
    }

    @Test fun phonePoseTracksItsOwnStepsAndFailuresWithoutReplacingTheAnimation() {
        val match = NoticeTask("t2", "main", null, false, "game")
        val tasks = listOf(NoticeTask("t1", "virtual", 8, false, "other"), match)
        val store = DesktopPetActivity
        val owner = Any()
        val failure = DesktopPetActivity.Result("game", "tool-game-15", "error", null)
        val answer = DesktopPetActivity.Result("game", "turn-game-19", "answer", "Done")
        store.attach(owner)
        try {
            store.publish(owner, "working", "game", null, "12", "打开游戏")
            val launch = store.status().display(tasks)
            assertEquals("phone", launch.pose)
            assertEquals("game", launch.sessionId)
            assertEquals("打开游戏", launch.caption)
            assertEquals(match, launch.progressTask)
            store.publish(owner, "thinking", "game", null, "13", "寻找入口", "screen")
            val next = store.status().display(tasks)
            assertEquals(launch.pose, next.pose)
            assertEquals("寻找入口", next.caption)
            assertNull(next.phoneStep)
            store.publish(owner, "thinking", "game", null, "13", null, "screen")
            assertEquals("screen", store.status().display(tasks).phoneStep)
            assertFalse(PetSpeechPause(launch.pose, launch.sessionId, launch.phase, 100)
                .holds(next.pose, next.sessionId, next.phase, null, 101))
            store.publish(owner, "working", "game", failure, "14", "打开菜单", "error")
            assertEquals(failure, store.status().display(tasks).result)
            assertEquals("error", store.status().display(tasks).phoneStep)
            assertNull(store.status().display(tasks).progressTask)
            store.publish(owner, "thinking", "game", null, "16")
            assertNull(store.status().display(tasks).result)
            assertNull(store.status().display(tasks).caption)
            assertEquals("thinking", store.status().display(tasks).speechState)
            store.publish(owner, "idle", null, answer)
            assertEquals(answer, store.status().display(listOf(match.copy(parked = true))).result)
            assertNull(store.status().display(listOf(match.copy(parked = true))).progressTask)
        } finally {
            store.dismiss(answer)
            store.detach(owner)
        }
    }

    @Test fun phoneNeverBorrowsAnotherSessionsCaptionAndQuestionsTakePriority() {
        val phone = listOf(NoticeTask("t", "main", null, false, "game"))
        val writing = DesktopPetActivity.Status("working", "editor", "write", caption = "编辑文件", phoneStep = "error")
        val separate = writing.display(phone)
        assertEquals("game", separate.sessionId)
        assertNull(separate.caption)
        assertNull(separate.result)
        assertNull(separate.phoneStep)
        val manual = phone.single().copy(caption = "我在等下一题")
        assertEquals(manual.caption, DesktopPetActivity.Status().display(listOf(manual)).caption)
        assertEquals(manual.caption, writing.display(listOf(manual)).caption)
        val asking = writing.copy(state = "asking", caption = "选哪一项？").display(phone)
        assertEquals("asking", asking.pose)
        assertEquals("editor", asking.sessionId)
        assertEquals("选哪一项？", asking.caption)
        assertNull(asking.progressTask)
        assertEquals(asking, writing.copy(state = "asking", caption = "选哪一项？").display(emptyList()))
        val settled = writing.display(listOf(phone.single().copy(parked = true)))
        assertEquals("working", settled.pose)
        assertNull(settled.caption)
        assertNull(settled.phoneStep)
    }

    @Test fun tappingAPhasePausesItUntilNewProgressOrFourSeconds() {
        val newResult = DesktopPetActivity.Result("same", "tool-14", "operation", "Done")
        for (pose in listOf("working", "thinking", "asking", "phone", "coding")) {
            val pause = PetSpeechPause(pose, "same", "step", 500)
            assertTrue(pause.holds(pose, "same", "step", null, 500))
            assertTrue(pause.holds(pose, "same", "step", null, 4499))
            assertFalse(pause.holds(pose, "same", "step", null, 4500))
            assertFalse(pause.holds(pose, "same", "next", null, 501))
            assertFalse(pause.holds(pose, "other", "step", null, 501))
            assertFalse(pause.holds(pose, "same", "step", newResult, 501))
            assertFalse(pause.holds("idle", null, null, null, 501))
        }
    }

    @Test fun spokenReplySurvivesObservationsButYieldsToAQuestionOrError() {
        val store = DesktopPetActivity
        val owner = Any()
        val task = NoticeTask("t", "main", null, false, "speech", caption = "等待下一回合")
        val first = DesktopPetActivity.Result("speech", "say-611", "reply", "你刚才通过了。")
        val second = first.copy(id = "say-612", text = "还有新的画面。")
        store.attach(owner)
        try {
            store.publish(owner, "working", "speech", first, "611")
            store.publish(owner, "thinking", "speech", null, "612", null, "screen")
            assertEquals(first, store.status().display(listOf(task)).result)
            assertNull(store.status().display(listOf(task)).progressTask)
            assertEquals(first, store.status().display(emptyList()).result)
            store.publish(owner, "working", "speech", second, "613")
            store.dismiss(first)
            assertEquals(second, store.status().display(listOf(task)).result)
            store.dismiss(second)
            assertNull(store.status().display(listOf(task)).result)
            store.publish(owner, "thinking", "speech", null, "614", "通用工具描述", "screen")
            assertEquals("等待下一回合", store.status().display(listOf(task)).caption)
            assertNull(store.status().display(listOf(task)).phoneStep)
            assertNull(store.status().display(listOf(task.copy(caption = ""))).caption)
            store.publish(owner, "asking", "speech", second.copy(id = "say-615"), "question", "继续吗？")
            assertNull(store.status().display(listOf(task)).result)
            assertEquals("继续吗？", store.status().display(listOf(task)).caption)
            store.publish(owner, "working", "speech", first.copy(id = "say-616"), "616")
            store.publish(owner, "working", "speech", null, "616", null, "error")
            assertEquals("error", store.status().display(listOf(task)).phoneStep)
            assertNull(store.status().display(listOf(task)).result)
            store.publish(owner, "working", "speech", first.copy(id = "say-617"), "617")
            store.publish(owner, "thinking", "elsewhere", null, "618")
            assertNull(store.status().display(listOf(task)).result)
        } finally {
            store.detach(owner)
        }
    }
}
