package com.zcode.ideaplugin.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @ 文件补全候选打分（FileMentionMatcher，issue #38）单元测试。
 *
 * 锁定：分隔符等价归一（abc_def ≡ abc-def ≡ abc.def，/ 也参与）、
 * 子序列层跨分隔符命中（输 abcd / agh 命中 abc_def_gh.py）、
 * 档位顺序（文件名前缀 > 文件名含 > 路径含 > 文件名子序列 > 路径子序列，
 * 同档短路径优先）、乱序不命中、空查询退化长度序。
 */
class FileMentionMatcherTest {

    @Test
    fun `issue 主例 输abcd跨下划线命中abc_def_gh_py`() {
        // 归一后 "abc-def-gh-py" 不含 "abcd"，落子序列层
        val rank = FileMentionMatcher.rank("abcd", "abc_def_gh.py", "src/abc_def_gh.py")
        assertNotNull(rank)
        // 同词精确命中（文件名含）必须排在模糊命中之前
        val exact = FileMentionMatcher.rank("abcd", "abcd.md", "x/abcd.md")
        assertNotNull(exact)
        assertTrue(exact < rank)
    }

    @Test
    fun `分隔符等价 下划线连字符点号空格路径斜杠互通`() {
        for (q in listOf("abc_def", "abc-def", "abc.def", "abc def", "abc/def")) {
            val rank = FileMentionMatcher.rank(q, "abc-def.py", "pkg/abc-def.py")
            assertNotNull(rank, "query=$q 应命中")
            // 命中在文件名含档（含前缀档更前），远优于子序列档
            assertTrue(rank < 2 * (1 shl 24), "query=$q 应在 contains 档命中")
        }
    }

    @Test
    fun `子序列层 输agh散点命中`() {
        assertNotNull(FileMentionMatcher.rank("agh", "abc_def_gh.py", "src/abc_def_gh.py"))
        assertNotNull(FileMentionMatcher.rank("SRCA", "App.kt", "src/a/App.kt"))
    }

    @Test
    fun `乱序或缺失字符不命中`() {
        assertNull(FileMentionMatcher.rank("dcba", "abc_def_gh.py", "src/abc_def_gh.py"))
        assertNull(FileMentionMatcher.rank("abcz", "abc_def_gh.py", "src/abc_def_gh.py"))
    }

    @Test
    fun `文件名前缀档优先于文件名含档`() {
        val prefix = FileMentionMatcher.rank("app", "app.kt", "a/app.kt")
        val contains = FileMentionMatcher.rank("app", "happy_app.kt", "happy_app.kt")
        assertNotNull(prefix)
        assertNotNull(contains)
        assertTrue(prefix < contains)
    }

    @Test
    fun `路径含档优先于文件名子序列档`() {
        // "pkg" 只在路径里（contains），另一候选文件名子序列散点命中
        val pathContains = FileMentionMatcher.rank("pkg", "main.kt", "pkg/main.kt")
        val nameSubseq = FileMentionMatcher.rank("pkg", "package_lock.js", "x/package_lock.js")
        assertNotNull(pathContains)
        assertNotNull(nameSubseq)
        assertTrue(pathContains < nameSubseq)
    }

    @Test
    fun `同档短路径优先`() {
        val short = FileMentionMatcher.rank("abc", "abc.py", "abc.py")
        val long = FileMentionMatcher.rank("abc", "abc.py", "deep/dir/tree/abc.py")
        assertNotNull(short)
        assertNotNull(long)
        assertTrue(short < long)
    }

    @Test
    fun `大小写不敏感`() {
        assertNotNull(FileMentionMatcher.rank("ABC", "abc_def_gh.py", "src/abc_def_gh.py"))
        assertNotNull(FileMentionMatcher.rank("Abc", "ABC_DEF_GH.py", "src/ABC_DEF_GH.py"))
    }

    @Test
    fun `空查询全匹配退化为长度序`() {
        assertEquals(7, FileMentionMatcher.rank("", "app.kt", "src/app"))
        assertEquals(0, FileMentionMatcher.rank("", "", ""))
    }
}
