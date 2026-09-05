#ifndef TRACKERCONTROL_NATIVE_TEST_JNI_H
#define TRACKERCONTROL_NATIVE_TEST_JNI_H

#include <stdint.h>

typedef const struct JNINativeInterface *JNIEnv;
typedef void *jobject;
typedef void *jclass;
typedef void *jmethodID;
typedef void *jfieldID;
typedef int32_t jint;
typedef int64_t jlong;
typedef uint8_t jboolean;
typedef uint32_t __be32;
typedef uint16_t __be16;

#define JNIEXPORT
#define JNICALL

#ifndef __packed
#define __packed __attribute__((packed))
#endif

#endif
