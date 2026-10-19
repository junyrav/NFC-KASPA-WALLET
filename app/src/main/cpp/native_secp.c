#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <secp256k1.h>
#include <secp256k1_extrakeys.h>
#include <secp256k1_schnorrsig.h>

/* One randomized, read-only context per loaded library. Java initialization serializes creation.
 * Secret scalar arithmetic stays inside pinned libsecp256k1; no custom curve/nonce arithmetic. */
static secp256k1_context *ctx = NULL;
static void wipe(void *p, size_t n) { volatile unsigned char *v = p; while (n--) *v++ = 0; }
static void fail(JNIEnv *e, const char *message) {
    jclass c = (*e)->FindClass(e, "java/lang/IllegalArgumentException");
    if (c) (*e)->ThrowNew(e, c, message);
}
static int read32(JNIEnv *e, jbyteArray in, unsigned char out[32]) {
    if (!in || (*e)->GetArrayLength(e, in) != 32) { fail(e, "Expected 32 bytes"); return 0; }
    (*e)->GetByteArrayRegion(e, in, 0, 32, (jbyte *)out);
    return !(*e)->ExceptionCheck(e);
}
static int ready(JNIEnv *e) { if (ctx) return 1; fail(e, "Native signing engine not initialized"); return 0; }
JNIEXPORT jboolean JNICALL Java_com_nfckaspa_wallet_NativeSecp_nativeInitialize(JNIEnv *e, jobject self, jbyteArray seed) {
    (void)self; unsigned char bytes[32];
    if (ctx || !read32(e, seed, bytes)) return JNI_FALSE;
    ctx = secp256k1_context_create(SECP256K1_CONTEXT_NONE);
    int ok = ctx && secp256k1_context_randomize(ctx, bytes);
    wipe(bytes, sizeof(bytes));
    if (!ok && ctx) { secp256k1_context_destroy(ctx); ctx = NULL; }
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_nfckaspa_wallet_NativeSecp_nativeValidKey(JNIEnv *e, jobject self, jbyteArray key) {
    (void)self; unsigned char bytes[32];
    if (!ready(e) || !read32(e, key, bytes)) return JNI_FALSE;
    int ok = secp256k1_ec_seckey_verify(ctx, bytes); wipe(bytes, sizeof(bytes));
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jbyteArray JNICALL Java_com_nfckaspa_wallet_NativeSecp_nativePublicKey(JNIEnv *e, jobject self, jbyteArray key) {
    (void)self; unsigned char bytes[32], compressed[33]; size_t length = sizeof(compressed); secp256k1_pubkey pub;
    if (!ready(e) || !read32(e, key, bytes)) return NULL;
    int ok = secp256k1_ec_pubkey_create(ctx, &pub, bytes); wipe(bytes, sizeof(bytes));
    if (!ok || !secp256k1_ec_pubkey_serialize(ctx, compressed, &length, &pub, SECP256K1_EC_COMPRESSED)) { fail(e, "Invalid private key"); return NULL; }
    jbyteArray out = (*e)->NewByteArray(e, 32);
    if (out) (*e)->SetByteArrayRegion(e, out, 0, 32, (jbyte *)(compressed + 1));
    return out;
}
JNIEXPORT jboolean JNICALL Java_com_nfckaspa_wallet_NativeSecp_nativeValidPublicKey(JNIEnv *e, jobject self, jbyteArray key) {
    (void)self; unsigned char bytes[32]; secp256k1_xonly_pubkey pub;
    if (!ready(e) || !read32(e, key, bytes)) return JNI_FALSE;
    return secp256k1_xonly_pubkey_parse(ctx, &pub, bytes) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jbyteArray JNICALL Java_com_nfckaspa_wallet_NativeSecp_nativeSign(JNIEnv *e, jobject self, jbyteArray msg, jbyteArray key, jbyteArray aux) {
    (void)self; unsigned char m[32] = {0}, k[32] = {0}, a[32] = {0}, sig[64] = {0}; secp256k1_keypair pair;
    int ok = ready(e) && read32(e, msg, m) && read32(e, key, k) && read32(e, aux, a);
    if (ok) ok = secp256k1_keypair_create(ctx, &pair, k);
    if (ok) ok = secp256k1_schnorrsig_sign32(ctx, sig, m, &pair, a);
    wipe(k, sizeof(k)); wipe(a, sizeof(a)); wipe(&pair, sizeof(pair)); wipe(m, sizeof(m));
    if (!ok) { wipe(sig, sizeof(sig)); if (!(*e)->ExceptionCheck(e)) fail(e, "Native Schnorr signing failed"); return NULL; }
    jbyteArray out = (*e)->NewByteArray(e, 64);
    if (out) (*e)->SetByteArrayRegion(e, out, 0, 64, (jbyte *)sig);
    wipe(sig, sizeof(sig)); return out;
}
JNIEXPORT jboolean JNICALL Java_com_nfckaspa_wallet_NativeSecp_nativeVerify(JNIEnv *e, jobject self, jbyteArray msg, jbyteArray key, jbyteArray signature) {
    (void)self; unsigned char m[32], p[32], sig[64]; secp256k1_xonly_pubkey pub;
    if (!ready(e) || !read32(e, msg, m) || !read32(e, key, p)) return JNI_FALSE;
    if (!signature || (*e)->GetArrayLength(e, signature) != 64) { fail(e, "Expected 64-byte signature"); return JNI_FALSE; }
    (*e)->GetByteArrayRegion(e, signature, 0, 64, (jbyte *)sig);
    if ((*e)->ExceptionCheck(e) || !secp256k1_xonly_pubkey_parse(ctx, &pub, p)) return JNI_FALSE;
    return secp256k1_schnorrsig_verify(ctx, sig, m, sizeof(m), &pub) ? JNI_TRUE : JNI_FALSE;
}
