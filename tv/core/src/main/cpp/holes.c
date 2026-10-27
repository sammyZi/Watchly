// The one thing Kotlin cannot ask Android for: free a stretch of a file in place.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/falloc.h>
#include <unistd.h>

/* Frees [offset, offset + length) of the file at path, its size unchanged; 0, or -errno. */
JNIEXPORT jint JNICALL
Java_com_torrentviewer_core_Holes_punchHole(JNIEnv *env, jclass cls, jstring path, jlong offset, jlong length) {
    (void) cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return -ENOMEM;
    int fd = open(p, O_WRONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (fd < 0) return -errno;
    // 64-bit offsets on the 32-bit TV too: films run past 4GB.
    int r = fallocate64(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off64_t) offset, (off64_t) length);
    int err = r < 0 ? -errno : 0;
    close(fd);
    return err;
}
