/*
Java native interface to C++ encoder

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

#include <jni.h>
#define assert(expr)
#include "encoder.hh"

static Interface *encoder;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_shredpix_MainActivity_createEncoder(
	JNIEnv *,
	jobject,
	jint sampleRate) {
	if (encoder && encoder->rate() == sampleRate)
		return true;
	delete encoder;
	switch (sampleRate) {
		case 8000:
			encoder = new(std::nothrow) Encoder<8000>();
			break;
		case 16000:
			encoder = new(std::nothrow) Encoder<16000>();
			break;
		case 32000:
			encoder = new(std::nothrow) Encoder<32000>();
			break;
		case 44100:
			encoder = new(std::nothrow) Encoder<44100>();
			break;
		case 48000:
			encoder = new(std::nothrow) Encoder<48000>();
			break;
		default:
			encoder = nullptr;
	}
	return encoder != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_shredpix_MainActivity_destroyEncoder(
	JNIEnv *,
	jobject) {
	delete encoder;
	encoder = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_shredpix_MainActivity_produceEncoder(
	JNIEnv *env,
	jobject,
	jshortArray JNI_audioBuffer,
	jint channelSelect) {

	if (!encoder)
		return false;

	jshort *audioBuffer = env->GetShortArrayElements(JNI_audioBuffer, nullptr);
	jboolean okay = false;
	if (audioBuffer)
		okay = encoder->produce(audioBuffer, channelSelect);
	env->ReleaseShortArrayElements(JNI_audioBuffer, audioBuffer, 0);
	return okay;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_shredpix_MainActivity_configureEncoder(
	JNIEnv *env,
	jobject,
	jbyteArray JNI_payload,
	jbyteArray JNI_callSign,
	jint operationMode,
	jint carrierFrequency,
	jint noiseSymbols,
	jboolean fancyHeader) {

	if (!encoder)
		return;

	jbyte *payload = nullptr, *callSign;
	if (operationMode) {
		payload = env->GetByteArrayElements(JNI_payload, nullptr);
		if (!payload)
			goto payloadFail;
	}
	callSign = env->GetByteArrayElements(JNI_callSign, nullptr);
	if (!callSign)
		goto callSignFail;

	encoder->configure(
		reinterpret_cast<uint8_t *>(payload),
		reinterpret_cast<int8_t *>(callSign),
		operationMode,
		carrierFrequency,
		noiseSymbols,
		fancyHeader);

	env->ReleaseByteArrayElements(JNI_callSign, callSign, JNI_ABORT);
	callSignFail:
	if (operationMode)
		env->ReleaseByteArrayElements(JNI_payload, payload, JNI_ABORT);
	payloadFail:;
}
