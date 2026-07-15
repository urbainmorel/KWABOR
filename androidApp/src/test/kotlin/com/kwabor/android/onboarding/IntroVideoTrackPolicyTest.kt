package com.kwabor.android.onboarding

import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileMain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntroVideoTrackPolicyTest {
    @Test
    fun portraitH264BaselineOrMainWithoutAudioIsAccepted() {
        assertTrue(listOf(videoTrack(AVCProfileBaseline)).isSupportedIntroVideoTracks())
        assertTrue(listOf(videoTrack(AVCProfileMain)).isSupportedIntroVideoTracks())
    }

    @Test
    fun audioTrackIsRejectedEvenWithSupportedVideo() {
        val tracks = listOf(
            videoTrack(AVCProfileMain),
            IntroVideoTrackMetadata(mimeType = "audio/mp4a-latm"),
        )

        assertFalse(tracks.isSupportedIntroVideoTracks())
    }

    @Test
    fun highProfileAndLandscapeVideoAreRejected() {
        assertFalse(listOf(videoTrack(AVCProfileHigh)).isSupportedIntroVideoTracks())
        assertFalse(
            listOf(
                IntroVideoTrackMetadata(
                    mimeType = "video/avc",
                    width = 1280,
                    height = 720,
                    avcProfile = AVCProfileMain,
                ),
            ).isSupportedIntroVideoTracks(),
        )
    }

    @Test
    fun multipleVideoTracksAreRejected() {
        val tracks = listOf(
            videoTrack(AVCProfileBaseline),
            videoTrack(AVCProfileMain),
        )

        assertFalse(tracks.isSupportedIntroVideoTracks())
    }

    @Test
    fun absentContainerProfileMetadataIsAcceptedWithoutGuessing() {
        assertTrue(listOf(videoTrack(profile = null)).isSupportedIntroVideoTracks())
    }

    private fun videoTrack(profile: Int?): IntroVideoTrackMetadata = IntroVideoTrackMetadata(
        mimeType = "video/avc",
        width = 720,
        height = 1280,
        avcProfile = profile,
    )
}
