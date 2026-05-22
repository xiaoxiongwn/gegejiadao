package com.example.searchfloat.util

import com.example.searchfloat.data.Question

object QuestionMatcher {

    fun detectQuestionType(text: String): String? {
        val t = text.lowercase()
        return when {
            t.contains("判断题") || t.contains("【判断】") -> "判断"
            t.contains("多选题") || t.contains("多项选择") || t.contains("【多选】") -> "多选"
            t.contains("单选题") || t.contains("单项选择") || t.contains("【单选】") -> "单选"
            else -> null
        }
    }

    data class MatchResult(val question: Question?, val score: Int, val titleLen: Int)

    /** 返回最佳匹配及评分。score / titleLen 比值 >= 0.5 才算可信。 */
    fun findBestMatchScored(screenText: String, questions: List<Question>): MatchResult {
        if (questions.isEmpty()) return MatchResult(null, 0, 0)

        val questionType = detectQuestionType(screenText)
        val screen = screenText.lowercase()

        var bestQ: Question? = null
        var bestScore = 0
        var bestLen = 0

        for (q in questions) {
            val (raw, len) = matchTitleScore(q.title, screen)
            if (len == 0) continue
            var score = raw
            // 题型加分/扣分
            if (questionType != null) {
                val cat = q.category
                if (cat.contains(questionType)) {
                    score += len  // 题型对了，重奖
                } else {
                    // 题型不匹配（屏幕是单选，题库这道是判断 等），扣分
                    val otherTypes = listOf("单选", "多选", "判断").filter { it != questionType }
                    if (otherTypes.any { cat.contains(it) }) {
                        score -= len  // 大幅扣分
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestQ = q
                bestLen = len
            }
        }
        return MatchResult(bestQ, bestScore, bestLen)
    }

    fun findBestMatch(screenText: String, questions: List<Question>): Question? {
        val r = findBestMatchScored(screenText, questions)
        // 阈值：原始匹配长度至少占题目标题长度的 50%
        if (r.question == null || r.titleLen == 0) return null
        if (r.score * 2 < r.titleLen) return null
        return r.question
    }

    /** 返回 (匹配字数, 题目标题字数)。匹配字数 = 题目里被屏幕包含的有效字符数。 */
    private fun matchTitleScore(title: String, screen: String): Pair<Int, Int> {
        val t = title.lowercase().trim()
        if (t.isEmpty()) return 0 to 0
        var matched = 0

        // 整句直接包含 → 满分
        if (t.length >= 4 && screen.contains(t)) {
            return t.length * 2 to t.length
        }

        // 滑动窗口：3 字以上的片段被屏幕包含才算
        var i = 0
        while (i < t.length) {
            var bestLen = 0
            var j = (t.length).coerceAtMost(i + 30)
            while (j - i >= 3) {
                val sub = t.substring(i, j)
                if (screen.contains(sub)) { bestLen = j - i; break }
                j--
            }
            if (bestLen >= 3) {
                matched += bestLen
                i += bestLen
            } else {
                i++
            }
        }
        return matched to t.length
    }
}
