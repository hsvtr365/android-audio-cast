package app.syntheticresin.audiocast

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAppInfoTest {
    @Test fun excludesPausedAndRemotePlayback() {
        assertEquals(listOf("local.music"),MediaAppInfo.playingPackages(listOf(
            MediaAppInfo.Candidate("paused.music",false,true),
            MediaAppInfo.Candidate("cast.music",true,false),
            MediaAppInfo.Candidate("local.music",true,true))))
    }
    @Test fun countsAppsRatherThanDuplicateSessionsAndAllowsNoPlayingApp() {
        assertEquals(listOf("first","second"),MediaAppInfo.playingPackages(listOf(
            MediaAppInfo.Candidate("first",true,true),MediaAppInfo.Candidate("first",true,true),MediaAppInfo.Candidate("second",true,true))))
        assertEquals(emptyList<String>(),MediaAppInfo.playingPackages(emptyList()))
    }
}
