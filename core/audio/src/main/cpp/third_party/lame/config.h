/* Minimal config.h for building LAME 3.100 with CMake (Android NDK and desktop tests). */
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H
#define STDC_HEADERS 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STRING_H 1
#define HAVE_STDLIB_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_UNISTD_H 1
#define HAVE_MEMCPY 1
#define HAVE_STRCHR 1
#define PACKAGE "lame"
#define VERSION "3.100"
#define LAME_LIBRARY_BUILD 1
/* Normally detected by configure. */
#define ieee754_float32_t float
#endif
