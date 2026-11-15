package com.autovideo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockRulesTest {
    @Test fun dc() = assertTrue(AdBlockRules.shouldBlock("https://googleads.g.doubleclick.net/pagead/id"))
    @Test fun syn() = assertTrue(AdBlockRules.shouldBlock("https://tpc.googlesyndication.com/x.js"))
    @Test fun adsvc() = assertTrue(AdBlockRules.shouldBlock("https://www.googleadservices.com/pagead/conversion"))
    @Test fun pagead() = assertTrue(AdBlockRules.shouldBlock("https://m.youtube.com/pagead/viewthroughconversion/1"))
    @Test fun stats() = assertTrue(AdBlockRules.shouldBlock("https://m.youtube.com/api/stats/ads?ver=2"))
    @Test fun ptrack() = assertTrue(AdBlockRules.shouldBlock("https://www.youtube.com/ptracking?pltype=adhost"))
    @Test fun midroll() = assertTrue(AdBlockRules.shouldBlock("https://www.youtube.com/get_midroll_info?x=1"))
    @Test fun video() = assertFalse(AdBlockRules.shouldBlock("https://rr3---sn-abc.googlevideo.com/videoplayback?id=1"))
    @Test fun page() = assertFalse(AdBlockRules.shouldBlock("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
    @Test fun player() = assertFalse(AdBlockRules.shouldBlock("https://m.youtube.com/youtubei/v1/player?key=x"))
    @Test fun lookalike() = assertFalse(AdBlockRules.shouldBlock("https://notdoubleclick.net.example.com/"))
    @Test fun garbage() = assertFalse(AdBlockRules.shouldBlock("not a url"))
}
