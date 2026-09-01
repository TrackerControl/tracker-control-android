/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2019 by Marcel Bokhorst (M66B)
*/

#include "netguard.h"

FILE *pcap_file = NULL;
size_t pcap_record_size = 64;
long pcap_file_size = 2 * 1024 * 1024;

// Guards pcap_file (open/close/write) against concurrent access from the
// packet threads (write_pcap_rec, from ip.c/tcp.c/udp.c/icmp.c) and whatever
// thread toggles capture via jni_pcap()/jni_init()/jni_done(). Every function
// below that touches pcap_file either takes this lock itself or is documented
// as requiring the caller to already hold it.
pthread_mutex_t pcap_lock = PTHREAD_MUTEX_INITIALIZER;

// Caller must hold pcap_lock and pcap_file must be non-NULL.
static void write_pcap_locked(const void *ptr, size_t len) {
    if (fwrite(ptr, len, 1, pcap_file) < 1)
        log_android(ANDROID_LOG_ERROR, "PCAP fwrite error %d: %s", errno, strerror(errno));
    else {
        long fsize = ftell(pcap_file);
        log_android(ANDROID_LOG_VERBOSE, "PCAP wrote %d @%ld", len, fsize);

        if (fsize > pcap_file_size) {
            log_android(ANDROID_LOG_WARN, "PCAP truncate @%ld", fsize);
            if (fflush(pcap_file))
                log_android(ANDROID_LOG_ERROR, "PCAP fflush error %d: %s",
                            errno, strerror(errno));
            else if (ftruncate(fileno(pcap_file), sizeof(struct pcap_hdr_s)))
                log_android(ANDROID_LOG_ERROR, "PCAP ftruncate error %d: %s",
                            errno, strerror(errno));
            else {
                if (fseek(pcap_file, sizeof(struct pcap_hdr_s), SEEK_SET))
                    log_android(ANDROID_LOG_ERROR, "PCAP ftruncate error %d: %s",
                                errno, strerror(errno));
            }
        }
    }
}

// Caller must hold pcap_lock and pcap_file must be non-NULL (called from
// jni_pcap() right after opening a fresh capture file, under the same lock).
void write_pcap_hdr_locked() {
    struct pcap_hdr_s pcap_hdr;
    pcap_hdr.magic_number = 0xa1b2c3d4;
    pcap_hdr.version_major = 2;
    pcap_hdr.version_minor = 4;
    pcap_hdr.thiszone = 0;
    pcap_hdr.sigfigs = 0;
    pcap_hdr.snaplen = pcap_record_size;
    pcap_hdr.network = LINKTYPE_RAW;
    write_pcap_locked(&pcap_hdr, sizeof(struct pcap_hdr_s));
}

void write_pcap_rec(const uint8_t *buffer, size_t length) {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts))
        log_android(ANDROID_LOG_ERROR, "clock_gettime error %d: %s", errno, strerror(errno));

    size_t plen = (length < pcap_record_size ? length : pcap_record_size);
    size_t rlen = sizeof(struct pcaprec_hdr_s) + plen;
    struct pcaprec_hdr_s *pcap_rec = ng_malloc(rlen, "pcap");

    pcap_rec->ts_sec = (guint32_t) ts.tv_sec;
    pcap_rec->ts_usec = (guint32_t) (ts.tv_nsec / 1000);
    pcap_rec->incl_len = (guint32_t) plen;
    pcap_rec->orig_len = (guint32_t) length;

    memcpy(((uint8_t *) pcap_rec) + sizeof(struct pcaprec_hdr_s), buffer, plen);

    if (pthread_mutex_lock(&pcap_lock))
        log_android(ANDROID_LOG_ERROR, "pcap_lock lock failed");
    else {
        if (pcap_file != NULL)
            write_pcap_locked(pcap_rec, rlen);
        if (pthread_mutex_unlock(&pcap_lock))
            log_android(ANDROID_LOG_ERROR, "pcap_lock unlock failed");
    }

    ng_free(pcap_rec, __FILE__, __LINE__);
}

// Closes pcap_file if open. Caller must hold pcap_lock.
void pcap_close_locked() {
    if (pcap_file == NULL)
        return;

    int flags = fcntl(fileno(pcap_file), F_GETFL, 0);
    if (flags < 0 || fcntl(fileno(pcap_file), F_SETFL, flags & ~O_NONBLOCK) < 0)
        log_android(ANDROID_LOG_ERROR, "PCAP fcntl ~O_NONBLOCK error %d: %s",
                    errno, strerror(errno));

    if (fsync(fileno(pcap_file)))
        log_android(ANDROID_LOG_ERROR, "PCAP fsync error %d: %s", errno, strerror(errno));

    if (fclose(pcap_file))
        log_android(ANDROID_LOG_ERROR, "PCAP fclose error %d: %s", errno, strerror(errno));

    pcap_file = NULL;
}
