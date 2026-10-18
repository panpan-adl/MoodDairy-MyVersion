package com.example.mydiary.ui.chat

/** Available immediately after a self-report, including when the network is unavailable. */
internal data class ManualMoodCare(val mood: String, val introduction: String, val tips: List<String>) {
    companion object {
        fun forMood(label: String?): ManualMoodCare? = when (val mood = MoodChangeTracker.normalize(label)) {
            "sad" -> ManualMoodCare(mood, "难过的时候，可以先给自己一点空间。挑一件现在愿意做的小事就好。", listOf(
                "给自己一点照顾：喝口水，找个舒服的位置坐一会儿。",
                "写下一句此刻的感受，不用急着找到答案。",
                "如果愿意，和信任的人说一句：我今天有点难过。",
            ))
            "angry" -> ManualMoodCare(mood, "生气时，可以先照顾自己，再决定怎么回应。", listOf(
                "先暂停回复或争论，给自己留一点缓冲时间。",
                "放松攥紧的手和肩膀，按舒服的节奏呼吸几次。",
                "试着写下：让我生气的是……我希望的是……",
            ))
            "fear" -> ManualMoodCare(mood, "害怕的时候，先把注意力带回此刻，按自己的节奏来。", listOf(
                "如果周围让你不安，先去一个让你感觉安全的地方。",
                "感受脚踩地面的触感，看看身边能找到的三样东西。",
                "需要陪伴时，可以联系一位你信任的人。",
            ))
            "disgust" -> ManualMoodCare(mood, "不舒服或反感的感受值得被尊重，可以先留一点距离。", listOf(
                "暂时离开让你不适的内容或场景，给自己一点空间。",
                "喝口水，把注意力转向一件看着舒服的小物件。",
                "想一想此刻的界限：我不喜欢什么，我需要什么。",
            ))
            else -> null
        }
    }
}
