package com.daasuu.gpuv.composer;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.util.Log;
import android.util.Size;
import com.daasuu.gpuv.egl.filter.GlFilter;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;

// Refer: https://github.com/ypresto/android-transcoder/blob/master/lib/src/main/java/net/ypresto/androidtranscoder/engine/MediaTranscoderEngine.java

/**
 * Internal engine, do not use this directly.
 */
class GPUMp4ComposerEngine {
    private static final String TAG = "GPUMp4ComposerEngine";
    private static final double PROGRESS_UNKNOWN = -1.0;
    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private static final long PROGRESS_INTERVAL_STEPS = 10;
    private FileDescriptor inputFileDescriptor;
    private Uri srcUri;
    private Context context;
    private VideoComposer videoComposer;
    private IAudioComposer audioComposer;
    private MediaExtractor mediaExtractor;
    private MediaMuxer mediaMuxer;
    private ProgressCallback progressCallback;
    private long durationUs;
    private long endTimeUs = -1;
    private MediaMetadataRetriever mediaMetadataRetriever;


    void setDataSource(FileDescriptor fileDescriptor, Uri srcUri, Context context) {
        this.srcUri = srcUri;
        this.context = context;
        this.inputFileDescriptor = fileDescriptor;
    }

    void setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback;
    }


    void compose(
            final String destPath,
            final Size outputResolution,
            final GlFilter filter,
            final int bitrate,
            final boolean mute,
            final Rotation rotation,
            final Size inputResolution,
            final FillMode fillMode,
            final FillModeCustomItem fillModeCustomItem,
            final int timeScale,
            final boolean flipVertical,
            final boolean flipHorizontal,
            final long startTimeMs,
            final long endTimeMs,
            final boolean useAltEncoder
    ) throws IOException {


        try {
            mediaMetadataRetriever = new MediaMetadataRetriever();
            if (inputFileDescriptor != null)
                mediaMetadataRetriever.setDataSource(inputFileDescriptor);
            else
                mediaMetadataRetriever.setDataSource(context, srcUri);
            try {
                durationUs = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
                if (endTimeMs > 0) {
                    endTimeUs = endTimeMs * 1000;
                    durationUs = endTimeUs;
                } else {
                    endTimeUs = -1;
                }
                if (startTimeMs > 0) {
                    durationUs -= (startTimeMs * 1000);
                }
            } catch (NumberFormatException e) {
                durationUs = -1;
            }
            Log.d(TAG, "Duration (us): " + durationUs);

            int videoTrackIndex = 0;
            int audioTrackIndex = 1;

            ArrayList<String> mediaFormats = new ArrayList<>();
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_AVC);
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_HEVC);
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_MPEG4);
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_VP9);
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_H263);
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_MPEG2);
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_VP8);
            mediaFormats.add(MediaFormat.MIMETYPE_VIDEO_RAW);

            MuxRender muxRender;

            boolean success = false;

            for (int i = 0; i < mediaFormats.size(); i++) {
                for(int vidSize = 0; vidSize < 2; vidSize++) {
                    try {
                        mediaExtractor = new MediaExtractor();
                        if (inputFileDescriptor != null)
                            mediaExtractor.setDataSource(inputFileDescriptor);
                        else
                            mediaExtractor.setDataSource(context, srcUri, null);

                        mediaMuxer = new MediaMuxer(destPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                        Log.d(TAG, "****** Trying with format " + mediaFormats.get(i) + "********");
                        MediaFormat videoOutputFormat = MediaFormat.createVideoFormat(mediaFormats.get(i), outputResolution.getWidth(), outputResolution.getHeight());

                        Log.d(TAG, "DIMS: " + inputResolution.getWidth() + " " + inputResolution.getHeight());

                        if(vidSize == 0) {
                            videoOutputFormat.setInteger(MediaFormat.KEY_WIDTH, inputResolution.getWidth());
                            videoOutputFormat.setInteger(MediaFormat.KEY_HEIGHT, inputResolution.getHeight());
                        } else {
                            videoOutputFormat.setInteger(MediaFormat.KEY_WIDTH, 1920);
                            int height = 1080;
                            if(inputResolution.getHeight() > 0 && inputResolution.getWidth() > 0) {
                                height = (int)((inputResolution.getWidth() / 1920.0) * inputResolution.getHeight());
                            }
                            videoOutputFormat.setInteger(MediaFormat.KEY_HEIGHT, height);//inputResolution.getHeight());
                        }
                        videoOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                        videoOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                        videoOutputFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, 30);
                        videoOutputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                        //videoOutputFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
                        videoOutputFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
                        videoOutputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                        //videoOutputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
                        //videoOutputFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, inputResolution.getWidth());
                        //videoOutputFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, inputResolution.getHeight());
                        //videoOutputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputResolution.getHeight() * inputResolution.getWidth());

                        muxRender = new MuxRender(mediaMuxer);

                        // identify track indices
                        MediaFormat format = mediaExtractor.getTrackFormat(0);
                        String mime = format.getString(MediaFormat.KEY_MIME);

                        if (mime.startsWith("video/")) {
                            videoTrackIndex = 0;
                            audioTrackIndex = 1;
                        } else {
                            videoTrackIndex = 1;
                            audioTrackIndex = 0;
                        }

                        // setup video composer
                        videoComposer = new VideoComposer(mediaExtractor, videoTrackIndex, videoOutputFormat, muxRender, timeScale);
                        videoComposer.setUp(filter, rotation, outputResolution, inputResolution, fillMode, fillModeCustomItem, flipVertical, flipHorizontal);
                        mediaExtractor.selectTrack(videoTrackIndex);

                        // setup audio if present and not muted
                        if (mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null/* && !mute*/) {
                            // has Audio video

                            if (timeScale < 2) {
                                audioComposer = new AudioComposer(mediaExtractor, audioTrackIndex, muxRender);
                            } else {
                                audioComposer = new RemixAudioComposer(mediaExtractor, audioTrackIndex, mediaExtractor.getTrackFormat(audioTrackIndex), muxRender, timeScale);
                            }

                            audioComposer.setup();

                            mediaExtractor.selectTrack(audioTrackIndex);

                            if (startTimeMs > 0)
                                mediaExtractor.seekTo((startTimeMs * 1000), MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                            runPipelines();
                        } else {
                            // no audio video
                            if (startTimeMs > 0)
                                mediaExtractor.seekTo((startTimeMs * 1000), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            runPipelinesNoAudio();
                        }

                        mediaMuxer.stop();

                        success = true;
                        Log.d(TAG, "Succeeded with mediaformat " + mediaFormats.get(i));
                    } catch (Exception e) {
                        Log.d(TAG, "Failed to init composer with media format " + mediaFormats.get(i));
                        e.printStackTrace();
                        try {
                            if (videoComposer != null) {
                                videoComposer.release();
                                videoComposer = null;
                            }
                            if (audioComposer != null) {
                                audioComposer.release();
                                audioComposer = null;
                            }
                            if (mediaExtractor != null) {
                                mediaExtractor.release();
                                mediaExtractor = null;
                            }
                            Thread.sleep(100);
                        } catch (Exception e1) {
                            e1.printStackTrace();
                        }
                        success = false;
                    }
                    if (success)
                        break;
                }
                if(success)
                    break;
            }
            if(!success)
                throw new IOException("Failed to create videoComposer with any of the specified codecs");
        } finally {
            try {
                if (videoComposer != null) {
                    videoComposer.release();
                    videoComposer = null;
                }
                if (audioComposer != null) {
                    audioComposer.release();
                    audioComposer = null;
                }
                if (mediaExtractor != null) {
                    mediaExtractor.release();
                    mediaExtractor = null;
                }
            } catch (RuntimeException e) {
                // Too fatal to make alive the app, because it may leak native resources.
                //noinspection ThrowFromFinallyBlock
                e.printStackTrace();
            }
            try {
                if (mediaMuxer != null) {
                    mediaMuxer.release();
                    mediaMuxer = null;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to release mediaMuxer.", e);
            }
            try {
                if (mediaMetadataRetriever != null) {
                    mediaMetadataRetriever.release();
                    mediaMetadataRetriever = null;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to release mediaMetadataRetriever.", e);
            }
        }


    }


    private void runPipelines() {
        long loopCount = 0;
        if (durationUs <= 0) {
            if (progressCallback != null) {
                progressCallback.onProgress(PROGRESS_UNKNOWN);
            }// unknown
        }
        while (!(videoComposer.isFinished() && audioComposer.isFinished())) {
            boolean stepped = videoComposer.stepPipeline()
                    || audioComposer.stepPipeline();
            loopCount++;
            if (durationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                double videoProgress = videoComposer.isFinished() ? 1.0 : Math.min(1.0, (double) videoComposer.getWrittenPresentationTimeUs() / durationUs);
                double audioProgress = audioComposer.isFinished() ? 1.0 : Math.min(1.0, (double) audioComposer.getWrittenPresentationTimeUs() / durationUs);
                double progress = (videoProgress + audioProgress) / 2.0;
                if (progressCallback != null) {
                    progressCallback.onProgress(progress);
                }
            }
            if(endTimeUs > 0 && videoComposer.getWrittenPresentationTimeUs() > endTimeUs) {
                Log.d("COMPOSER", "Breaking at end time " + endTimeUs);
                break;
            }
            if (!stepped) {
                try {
                    Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
                } catch (InterruptedException e) {
                    // nothing to do
                }
            }
        }
    }

    private void runPipelinesNoAudio() {
        long loopCount = 0;
        if (durationUs <= 0) {
            if (progressCallback != null) {
                progressCallback.onProgress(PROGRESS_UNKNOWN);
            } // unknown
        }
        while (!videoComposer.isFinished()) {
            boolean stepped = videoComposer.stepPipeline();
            loopCount++;
            if (durationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                double videoProgress = videoComposer.isFinished() ? 1.0 : Math.min(1.0, (double) videoComposer.getWrittenPresentationTimeUs() / durationUs);
                if (progressCallback != null) {
                    progressCallback.onProgress(videoProgress);
                }
            }
            if(endTimeUs > 0 && videoComposer.getWrittenPresentationTimeUs() > endTimeUs) {
                Log.d("COMPOSER", "Breaking at end time " + endTimeUs);
                break;
            }
            if (!stepped) {
                try {
                    Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
                } catch (InterruptedException e) {
                    // nothing to do
                }
            }
        }


    }


    interface ProgressCallback {
        /**
         * Called to notify progress. Same thread which initiated transcode is used.
         *
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onProgress(double progress);
    }
}
