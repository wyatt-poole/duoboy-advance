// JNI bridge: runs libmgba, exposes frame/audio/memory to Java.
#include <jni.h>
#include <string.h>
#include <android/log.h>
#include <mgba/flags.h>
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>
#include <fcntl.h>
#include <mgba-util/audio-buffer.h>
#include "libretro-audio.h"

#define W 240
#define H 160
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "gbads", __VA_ARGS__)

struct mCore* gbads_core;
#define core gbads_core
static mColor video[W * H];
// Games change the GBA's audio rate at runtime; resample to a fixed 65536 Hz exactly like mGBA's libretro core.
static struct LibretroAudioConverter conv;

#define FN(name) Java_dev_gbads_Core_##name

JNIEXPORT jint JNICALL FN(load)(JNIEnv* env, jclass cls, jstring rom, jstring sav) {
	const char* romPath = (*env)->GetStringUTFChars(env, rom, 0);
	const char* savPath = (*env)->GetStringUTFChars(env, sav, 0);
	jint rate = 0;
	core = mCoreFind(romPath);
	if (core && core->init(core)) {
		mCoreInitConfig(core, NULL);
		core->setVideoBuffer(core, video, W);
		core->setAudioBufferSize(core, 2048);
		// Plain .sav next to the ROM: byte-identical to what desktop mGBA reads/writes.
		if (mCoreLoadFile(core, romPath) && mCoreLoadSaveFile(core, savPath, false)) {
			core->reset(core);
			audioConverterReset(&conv, core->audioSampleRate(core));
			rate = GBA_OUTPUT_RATE;
		}
	}
	LOG("load %s -> %d", romPath, rate);
	(*env)->ReleaseStringUTFChars(env, rom, romPath);
	(*env)->ReleaseStringUTFChars(env, sav, savPath);
	return rate;
}

// Runs one frame. Fills `pixels` (ARGB_8888 bitmap layout) and `audio` (interleaved stereo); returns stereo frames written.
JNIEXPORT jint JNICALL FN(frame)(JNIEnv* env, jclass cls, jint keys, jobject pixels, jshortArray audio) {
	core->setKeys(core, keys);
	core->runFrame(core);
	uint32_t* out = (*env)->GetDirectBufferAddress(env, pixels);
	for (int i = 0; i < W * H; ++i) out[i] = video[i] | 0xFF000000; // mGBA leaves alpha 0
	struct mAudioBuffer* ab = core->getAudioBuffer(core);
	unsigned inRate = core->audioSampleRate(core);
	if (inRate != conv.inputRate) audioConverterReset(&conv, inRate);
	jsize cap = (*env)->GetArrayLength(env, audio) / 2;
	jshort* s = (*env)->GetShortArrayElements(env, audio, 0);
	static int16_t in[512 * 2];
	size_t n = 0, got;
	// ponytail: worst case 4x upsample per chunk; Java passes a buffer far larger than one frame needs
	while (n + 512 * 4 <= (size_t) cap && (got = mAudioBufferRead(ab, in, 512)))
		n += audioConverterProcess(&conv, in, got, s + n * 2);
	(*env)->ReleaseShortArrayElements(env, audio, s, 0);
	return (jint) n;
}

JNIEXPORT void JNICALL FN(read)(JNIEnv* env, jclass cls, jint addr, jbyteArray out) {
	jsize n = (*env)->GetArrayLength(env, out);
	jbyte* b = (*env)->GetByteArrayElements(env, out, 0);
	for (jsize i = 0; i < n; ++i) b[i] = core->rawRead8(core, (uint32_t) addr + i, -1);
	(*env)->ReleaseByteArrayElements(env, out, b, 0);
}

JNIEXPORT void JNICALL FN(write8)(JNIEnv* env, jclass cls, jint addr, jint v) {
	core->rawWrite8(core, (uint32_t) addr, -1, (uint8_t) v);
}

JNIEXPORT void JNICALL FN(write32)(JNIEnv* env, jclass cls, jint addr, jint v) {
	core->rawWrite32(core, (uint32_t) addr, -1, (uint32_t) v);
}

JNIEXPORT void JNICALL FN(reset)(JNIEnv* env, jclass cls) { core->reset(core); }

// Save states: everything but the battery save (loading a state never touches the real .sav/.srm).
#define STATE_FLAGS (SAVESTATE_RTC | SAVESTATE_METADATA)
static jboolean stateIo(JNIEnv* env, jstring path, int write) {
    const char* p = (*env)->GetStringUTFChars(env, path, 0);
    struct VFile* vf = VFileOpen(p, write ? O_CREAT | O_TRUNC | O_RDWR : O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!vf) return JNI_FALSE;
    bool ok = write ? mCoreSaveStateNamed(core, vf, STATE_FLAGS) : mCoreLoadStateNamed(core, vf, STATE_FLAGS);
    vf->close(vf);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL FN(saveState)(JNIEnv* env, jclass cls, jstring path) { return stateIo(env, path, 1); }
JNIEXPORT jboolean JNICALL FN(loadState)(JNIEnv* env, jclass cls, jstring path) { return stateIo(env, path, 0); }

JNIEXPORT void JNICALL FN(layer)(JNIEnv* env, jclass cls, jint id, jboolean on) {
	core->enableVideoLayer(core, id, on);
}
