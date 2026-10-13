package com.zcode.ideaplugin.ui

/**
 * @ 文件补全候选打分匹配（issue #38：输 abcd 也要能命中 abc_def_gh.py，
 * 对齐 ZCode 桌面版 / Kimi Code / Cline 的分隔符宽松匹配）。
 *
 * 分隔符 _ - . 空格 / 与大小写在两侧归一为同一形态（abc_def ≡ abc-def ≡ abc.def，
 * 路径分隔符 / 也参与等价，输 src main 可跨目录段命中）；归一后按五档降级：
 * 0 文件名前缀 / 1 文件名含 / 2 路径含 / 3 文件名子序列 / 4 路径子序列。
 * 子序列层允许跳过任意字符（顺序保持），输 agh 也能命中 abc_def_gh.py。
 * 同档内短路径优先（沿用原 contains 过滤的排序语义）。
 */
object FileMentionMatcher {

    private const val TIER_GAP = 1 shl 24

    /** 空查询全匹配：rank 退化为路径长度，等价原「全收 + 按长度排序」 */
    fun rank(rawQuery: String, name: String, path: String): Int? {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return path.length
        val nName = normalize(name)
        val nPath = normalize(path)
        val tier = when {
            nName.startsWith(query) -> 0
            nName.contains(query) -> 1
            nPath.contains(query) -> 2
            isSubsequence(query, nName) -> 3
            isSubsequence(query, nPath) -> 4
            else -> return null
        }
        return tier * TIER_GAP + path.length
    }

    private fun normalize(s: String): String = buildString(s.length) {
        for (c in s.lowercase()) append(if (c.isWhitespace() || c == '_' || c == '-' || c == '.' || c == '/') '-' else c)
    }

    private fun isSubsequence(query: String, target: String): Boolean {
        if (query.length > target.length) return false
        var qi = 0
        for (c in target) {
            if (qi < query.length && c == query[qi]) qi++
        }
        return qi == query.length
    }
}
