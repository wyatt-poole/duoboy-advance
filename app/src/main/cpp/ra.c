// RetroAchievements via rcheevos rc_client (the same library RetroArch uses). HTTP is done in Java (Ra.java).
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <mgba/flags.h>
#include <mgba/core/core.h>
#include <rc_client.h>
#include <rc_consoles.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "gbads", __VA_ARGS__)
#define FN(name) Java_dev_gbads_Ra_##name

extern struct mCore* gbads_core;
static rc_client_t* client;
static JavaVM* vm;
static jclass raClass;
static uint8_t *iwram, *ewram;

static JNIEnv* env_() {
	JNIEnv* env;
	if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK) (*vm)->AttachCurrentThread(vm, &env, NULL);
	return env;
}

// rcheevos GBA map: $0000-$7FFF IWRAM, $8000-$47FFF EWRAM, $48000-$57FFF SRAM.
static uint32_t readMemory(uint32_t addr, uint8_t* buf, uint32_t n, rc_client_t* c) {
	for (uint32_t i = 0; i < n; ++i, ++addr) {
		if (addr < 0x8000) buf[i] = iwram[addr];
		else if (addr < 0x48000) buf[i] = ewram[addr - 0x8000];
		else return i; // ponytail: SRAM region unmapped; Pokémon sets read WRAM. Map mGBA savedata if a set needs it.
	}
	return n;
}

static void serverCall(const rc_api_request_t* req, rc_client_server_callback_t cb, void* cbData, rc_client_t* c) {
	JNIEnv* env = env_();
	jmethodID m = (*env)->GetStaticMethodID(env, raClass, "request", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V");
	jstring url = (*env)->NewStringUTF(env, req->url);
	jstring post = req->post_data ? (*env)->NewStringUTF(env, req->post_data) : NULL;
	jstring type = req->content_type ? (*env)->NewStringUTF(env, req->content_type) : NULL;
	(*env)->CallStaticVoidMethod(env, raClass, m, url, post, type, (jlong) (intptr_t) cb, (jlong) (intptr_t) cbData);
}

JNIEXPORT void JNICALL FN(response)(JNIEnv* env, jclass cls, jlong cb, jlong cbData, jbyteArray body, jint status) {
	rc_api_server_response_t r = {0};
	jbyte* b = body ? (*env)->GetByteArrayElements(env, body, 0) : NULL;
	r.body = (const char*) b;
	r.body_length = body ? (*env)->GetArrayLength(env, body) : 0;
	r.http_status_code = status;
	((rc_client_server_callback_t) (intptr_t) cb)(&r, (void*) (intptr_t) cbData);
	if (b) (*env)->ReleaseByteArrayElements(env, body, b, JNI_ABORT);
}

// Events go to Java as (kind, title, description, badge URL).
static void notify(int kind, const char* title, const char* desc, const char* badge) {
	JNIEnv* env = env_();
	jmethodID m = (*env)->GetStaticMethodID(env, raClass, "event", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
	jstring t = (*env)->NewStringUTF(env, title ? title : ""), d = (*env)->NewStringUTF(env, desc ? desc : ""), u = (*env)->NewStringUTF(env, badge ? badge : "");
	(*env)->CallStaticVoidMethod(env, raClass, m, kind, t, d, u);
	(*env)->DeleteLocalRef(env, t); (*env)->DeleteLocalRef(env, d); (*env)->DeleteLocalRef(env, u);
}

static void onEvent(const rc_client_event_t* e, rc_client_t* c) {
	char url[256] = "";
	switch (e->type) {
	case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
		rc_client_achievement_get_image_url(e->achievement, RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED, url, sizeof(url));
		notify(e->type, e->achievement->title, e->achievement->description, url);
		break;
	case RC_CLIENT_EVENT_LEADERBOARD_STARTED: case RC_CLIENT_EVENT_LEADERBOARD_FAILED:
		notify(e->type, e->leaderboard->title, e->leaderboard->description, NULL);
		break;
	case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED:
		notify(e->type, e->leaderboard->title, e->leaderboard->tracker_value, NULL);
		break;
	case RC_CLIENT_EVENT_GAME_COMPLETED: {
		const rc_client_game_t* g = rc_client_get_game_info(c);
		rc_client_game_get_image_url(g, url, sizeof(url));
		notify(e->type, rc_client_get_hardcore_enabled(c) ? "Mastered" : "Completed", g->title, url);
		break;
	}
	case RC_CLIENT_EVENT_SERVER_ERROR:
		notify(e->type, e->server_error->api, e->server_error->error_message, NULL);
		break;
	case RC_CLIENT_EVENT_RESET: // enabling hardcore mid-game requires a reset, same as RetroArch
		notify(e->type, "Hardcore enabled", "Game reset", NULL);
		break;
	case RC_CLIENT_EVENT_DISCONNECTED: notify(e->type, "Disconnected", "Unlocks will be sent when back online", NULL); break;
	case RC_CLIENT_EVENT_RECONNECTED: notify(e->type, "Reconnected", "Pending unlocks sent", NULL); break;
	default: break;
	}
}

static void onLog(const char* msg, const rc_client_t* c) { LOG("rc: %s", msg); }

JNIEXPORT void JNICALL FN(init)(JNIEnv* env, jclass cls, jboolean hardcore) {
	(*env)->GetJavaVM(env, &vm);
	raClass = (*env)->NewGlobalRef(env, cls);
	size_t n;
	const struct mCoreMemoryBlock* blocks;
	n = gbads_core->listMemoryBlocks(gbads_core, &blocks);
	for (size_t i = 0; i < n; ++i) {
		size_t sz;
		if (blocks[i].start == 0x02000000) ewram = gbads_core->getMemoryBlock(gbads_core, blocks[i].id, &sz);
		if (blocks[i].start == 0x03000000) iwram = gbads_core->getMemoryBlock(gbads_core, blocks[i].id, &sz);
	}
	client = rc_client_create(readMemory, serverCall);
	rc_client_enable_logging(client, RC_CLIENT_LOG_LEVEL_INFO, onLog);
	rc_client_set_event_handler(client, onEvent);
	rc_client_set_hardcore_enabled(client, hardcore);
}

static void onLogin(int result, const char* error, rc_client_t* c, void* ud) {
	JNIEnv* env = env_();
	const rc_client_user_t* u = rc_client_get_user_info(c);
	jmethodID m = (*env)->GetStaticMethodID(env, raClass, "loggedIn", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
	jstring e = (*env)->NewStringUTF(env, error ? error : "");
	jstring name = (*env)->NewStringUTF(env, u ? u->username : ""), tok = (*env)->NewStringUTF(env, u ? u->token : "");
	(*env)->CallStaticVoidMethod(env, raClass, m, result, e, name, tok);
}

JNIEXPORT void JNICALL FN(loginPassword)(JNIEnv* env, jclass cls, jstring user, jstring pass) {
	const char* u = (*env)->GetStringUTFChars(env, user, 0); const char* p = (*env)->GetStringUTFChars(env, pass, 0);
	rc_client_begin_login_with_password(client, u, p, onLogin, NULL);
	(*env)->ReleaseStringUTFChars(env, user, u); (*env)->ReleaseStringUTFChars(env, pass, p);
}

JNIEXPORT void JNICALL FN(loginToken)(JNIEnv* env, jclass cls, jstring user, jstring token) {
	const char* u = (*env)->GetStringUTFChars(env, user, 0); const char* t = (*env)->GetStringUTFChars(env, token, 0);
	rc_client_begin_login_with_token(client, u, t, onLogin, NULL);
	(*env)->ReleaseStringUTFChars(env, user, u); (*env)->ReleaseStringUTFChars(env, token, t);
}

static void onGameLoaded(int result, const char* error, rc_client_t* c, void* ud) {
	const rc_client_game_t* g = rc_client_get_game_info(c);
	if (result != RC_OK || !g || !g->id) { notify(-1, "RetroAchievements", error ? error : "No achievements for this ROM", NULL); return; }
	rc_client_user_game_summary_t s;
	rc_client_get_user_game_summary(c, &s);
	char desc[128], url[256] = "";
	snprintf(desc, sizeof(desc), "%u of %u achievements%s", s.num_unlocked_achievements, s.num_promoted_achievements,
	         rc_client_get_hardcore_enabled(c) ? " (Hardcore)" : "");
	rc_client_game_get_image_url(g, url, sizeof(url));
	notify(-2, g->title, desc, url);
}

// RetroArch hashes GBA ROMs as the MD5 of the whole file; rc_client does the same from the path.
JNIEXPORT void JNICALL FN(loadGame)(JNIEnv* env, jclass cls, jstring path) {
	const char* p = (*env)->GetStringUTFChars(env, path, 0);
	rc_client_begin_identify_and_load_game(client, RC_CONSOLE_GAMEBOY_ADVANCE, p, NULL, 0, onGameLoaded, NULL);
	(*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT void JNICALL FN(doFrame)(JNIEnv* env, jclass cls) { if (client) rc_client_do_frame(client); }
JNIEXPORT void JNICALL FN(idle)(JNIEnv* env, jclass cls) { if (client) rc_client_idle(client); }
JNIEXPORT jboolean JNICALL FN(hardcore)(JNIEnv* env, jclass cls) { return client && rc_client_get_hardcore_enabled(client); }

JNIEXPORT void JNICALL FN(logout)(JNIEnv* env, jclass cls) { if (client) rc_client_logout(client); }
JNIEXPORT void JNICALL FN(setHardcore)(JNIEnv* env, jclass cls, jboolean on) { if (client) rc_client_set_hardcore_enabled(client, on); }
JNIEXPORT void JNICALL FN(reset)(JNIEnv* env, jclass cls) { if (client) rc_client_reset(client); }
