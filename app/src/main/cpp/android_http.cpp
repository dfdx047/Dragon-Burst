// Dragon Burst - HTTP(S) para o codigo do jogo, pela pilha de rede do Android.
//
// No PC o ecra inicial e o online do Burst Limit usam o WinHTTP. No Android o NDK
// nao traz TLS, por isso os pedidos vao para o Java (NativeHttp.get, com o
// HttpURLConnection do sistema: certificados, proxy e IPv6 como em qualquer app).
// burstlimit_launcher.cpp (patches/game/0001) declara DragonBurstHttpGet.

#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <functional>
#include <string>

namespace {

JavaVM* g_vm = nullptr;
jclass g_http_class = nullptr;
jmethodID g_http_get = nullptr;

// Liga a thread atual a JVM (as threads do jogo sao nativas). Fica ligada ate sair:
// o SDL e o runtime nao a reutilizam para outra coisa.
JNIEnv* CurrentEnv() {
  if (!g_vm) return nullptr;
  JNIEnv* env = nullptr;
  if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
    return env;
  }
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    return nullptr;
  }
  return env;
}

}  // namespace

// Chamado quando o GameActivity carrega a libgame.so (System.load, thread Java com o
// class loader da app): e aqui que FindClass encontra as classes da app.
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_VERSION_1_6;
  }
  jclass local = env->FindClass("com/dfdx047/dragonburst/net/NativeHttp");
  if (!local) {
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_WARN, "dragonburst", "NativeHttp em falta: sem HTTP no jogo");
    return JNI_VERSION_1_6;
  }
  g_http_class = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  g_http_get = env->GetStaticMethodID(g_http_class, "get", "(Ljava/lang/String;I)[B");
  if (!g_http_get) {
    env->ExceptionClear();
  }
  return JNI_VERSION_1_6;
}

// GET (http/https, redirecionamentos seguidos). O corpo inteiro vai para `sink` de
// uma vez, com o tamanho total. false = falhou (rede, estado HTTP != 2xx) ou o sink
// recusou.
bool DragonBurstHttpGet(const std::string& url, int timeout_ms,
                        const std::function<bool(const char*, size_t, uint64_t)>& sink) {
  if (!g_http_class || !g_http_get) return false;
  JNIEnv* env = CurrentEnv();
  if (!env) return false;
  jstring jurl = env->NewStringUTF(url.c_str());
  auto body = static_cast<jbyteArray>(
      env->CallStaticObjectMethod(g_http_class, g_http_get, jurl, jint(timeout_ms)));
  env->DeleteLocalRef(jurl);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  if (!body) return false;
  const jsize size = env->GetArrayLength(body);
  bool ok = true;
  if (size > 0) {
    jbyte* bytes = env->GetByteArrayElements(body, nullptr);
    ok = sink(reinterpret_cast<const char*>(bytes), size_t(size), uint64_t(size));
    env->ReleaseByteArrayElements(body, bytes, JNI_ABORT);
  }
  env->DeleteLocalRef(body);
  return ok;
}
