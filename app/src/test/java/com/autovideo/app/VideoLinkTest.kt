package com.autovideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLinkTest {
    @Test fun watch() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"))
    @Test fun mobile() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
    @Test fun short() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
    @Test fun shorts() = assertEquals("abcdefghijk", VideoLink.extractVideoId("https://youtube.com/shorts/abcdefghijk?si=x"))
    @Test fun live() = assertEquals("abcdefghijk", VideoLink.extractVideoId("https://www.youtube.com/live/abcdefghijk"))
    @Test fun embed() = assertEquals("abcdefghijk", VideoLink.extractVideoId("https://www.youtube.com/embed/abcdefghijk"))
    @Test fun music() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    @Test fun withText() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("Check this out\nhttps://youtu.be/dQw4w9WgXcQ"))
    @Test fun noScheme() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("youtu.be/dQw4w9WgXcQ"))
    @Test fun notYoutube() = assertNull(VideoLink.extractVideoId("https://vimeo.com/123456"))
    @Test fun lookalike() = assertNull(VideoLink.extractVideoId("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
    @Test fun badId() = assertNull(VideoLink.extractVideoId("https://youtube.com/watch?v=short"))
    @Test fun empty() = assertNull(VideoLink.extractVideoId(""))
    @Test fun url0() = assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", VideoLink.watchUrl("dQw4w9WgXcQ"))
    @Test fun urlT() = assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=95s", VideoLink.watchUrl("dQw4w9WgXcQ", 95))
}
