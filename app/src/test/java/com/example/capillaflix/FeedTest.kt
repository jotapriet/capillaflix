package com.example.capillaflix

import org.junit.Assert.*
import org.junit.Test
import org.kxml2.io.KXmlParser

class FeedTest {
    @Test fun atomExcludesShortsAndKeepsRegularVideosRegardlessOfTitle() {
        val xml = """<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">
          <author><name>Canal</name></author>
          <entry><yt:videoId>short</yt:videoId><title>Una marcha</title>
            <link rel="alternate" href="https://www.youtube.com/shorts/short"/>
            <published>2026-11-17T10:00:00Z</published></entry>
          <entry><yt:videoId>normal</yt:videoId><title>Un vídeo breve #shorts</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=normal"/>
            <published>2026-11-17T09:00:00Z</published></entry>
          <entry><link rel="alternate" href="https://www.youtube.com/shorts/another"/>
            <yt:videoId>another</yt:videoId><title>Otro short</title></entry>
          <entry><yt:videoId>last</yt:videoId><title>Otro vídeo</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=last"/></entry>
        </feed>"""
        val result = parseYoutubeAtom(xml, KXmlParser())
        assertEquals(listOf("normal", "last"), result.map { it.id })
        assertEquals("Canal", result.first().channel)
        assertEquals("Un vídeo breve #shorts", result.first().title)
    }
    @Test fun liveWinsOverNewerUploadAndDuplicate() {
        val result = orderVideos(listOf(Video("a", "Antiguo", "Canal", 100),
            Video("b", "Nuevo", "Canal", 300), Video("a", "Directo", "Canal", 50, true)))
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertTrue(result.first().live)
    }
    @Test fun upcomingAndFinishedStreamsAreNotLive() {
        val html = """<script>var ytInitialData = {"items":[
          {"compactVideoRenderer":{"videoId":"live","title":{"runs":[{"text":"En vivo"}]},"thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"LIVE"}}]}},
          {"videoRenderer":{"videoId":"upcoming","upcomingEventData":{},"thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"UPCOMING"}}]}},
          {"videoRenderer":{"videoId":"ended","thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"style":"DEFAULT"}}]}}
        ]};</script>"""
        assertEquals(listOf("live"), parseYoutubeLive(html, "Canal").map { it.id })
    }
    @Test fun handlesCurrentJavascriptEncodedResponse() {
        val html = """<script>var ytInitialData = '\x7b\x22contents\x22:\x5b\x5d\x7d';</script>"""
        assertTrue(parseYoutubeLive(html, "Canal").isEmpty())
        assertEquals("María\n'", decodeJavascriptString("Mar\\u00eda\\n\\'"))
    }
    @Test(expected = IllegalStateException::class) fun consentPageIsAnErrorInsteadOfNoLiveStreams() {
        parseYoutubeLive("<html>Consent</html>", "Canal")
    }
}
