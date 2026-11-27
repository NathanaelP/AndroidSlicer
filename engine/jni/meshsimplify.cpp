// SPDX-License-Identifier: AGPL-3.0-or-later
//
// libmeshsimplify: reduces a binary STL's triangle count with meshoptimizer's
// simplifier, for org.mobileslicer.engine.NativeSimplifier. Everything happens
// here, in native memory: the file is read and welded (corners with identical
// coordinates become one vertex), simplified, and written back out. Dense sculpts
// would not fit in the app's Java heap.

#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <new>

#include "meshoptimizer.h"

namespace {

// Result codes (negative); keep in step with NativeSimplifier.
constexpr jlong ERR_READ = -1;
constexpr jlong ERR_MEMORY = -2;
constexpr jlong ERR_WRITE = -3;

template <typename T>
struct Buf {  // a malloc'd array that frees itself
    T* p = nullptr;
    size_t n = 0;
    bool alloc(size_t count) {
        p = static_cast<T*>(std::malloc(count * sizeof(T)));
        n = count;
        return p != nullptr || count == 0;
    }
    ~Buf() { std::free(p); }
};

uint64_t hash3(uint32_t x, uint32_t y, uint32_t z) {
    uint64_t h = x * 0x9E3779B97F4A7C15ull;
    h = (h ^ y) * 0x9E3779B97F4A7C15ull;
    h = (h ^ z) * 0x9E3779B97F4A7C15ull;
    return h ^ (h >> 31);
}

struct Stl {
    uint32_t triangles = 0;
    Buf<float> positions;  // welded vertices, x y z
    size_t vertices = 0;
    Buf<uint32_t> indices;  // 3 per triangle, flat triangles dropped
    size_t indexCount = 0;
};

jlong readAndWeld(const char* path, Stl& m) {
    FILE* f = std::fopen(path, "rb");
    if (!f) return ERR_READ;
    unsigned char header[84];
    if (std::fread(header, 1, 84, f) != 84) { std::fclose(f); return ERR_READ; }
    std::memcpy(&m.triangles, header + 80, 4);
    size_t t = m.triangles;
    if (!m.indices.alloc(3 * t)) { std::fclose(f); return ERR_MEMORY; }
    // Vertices are at most 3 per triangle; sculpts have about half a vertex per triangle.
    size_t cap = 1024;
    while (cap < t * 2) cap <<= 1;  // table at most ~50% full for closed meshes
    Buf<uint32_t> table;
    if (!table.alloc(cap)) { std::fclose(f); return ERR_MEMORY; }
    std::memset(table.p, 0xff, cap * sizeof(uint32_t));
    size_t posCap = t / 2 + 16;
    if (!m.positions.alloc(3 * posCap)) { std::fclose(f); return ERR_MEMORY; }

    const size_t chunk = 8192;
    Buf<unsigned char> rec;
    if (!rec.alloc(50 * chunk)) { std::fclose(f); return ERR_MEMORY; }
    size_t done = 0;
    while (done < t) {
        size_t n = t - done < chunk ? t - done : chunk;
        if (std::fread(rec.p, 50, n, f) != n) { std::fclose(f); return ERR_READ; }
        for (size_t i = 0; i < n; i++) {
            uint32_t id[3];
            for (int k = 0; k < 3; k++) {
                uint32_t b[3];
                std::memcpy(b, rec.p + 50 * i + 12 + 12 * k, 12);
                size_t s = hash3(b[0], b[1], b[2]) & (cap - 1);
                while (true) {
                    uint32_t v = table.p[s];
                    if (v == 0xffffffffu) {
                        if (m.vertices == posCap) {
                            size_t nc = posCap * 2;
                            float* np = static_cast<float*>(std::realloc(m.positions.p, 3 * nc * sizeof(float)));
                            if (!np) { std::fclose(f); return ERR_MEMORY; }
                            m.positions.p = np; m.positions.n = 3 * nc; posCap = nc;
                        }
                        v = static_cast<uint32_t>(m.vertices++);
                        std::memcpy(m.positions.p + 3 * v, b, 12);
                        table.p[s] = v;
                        break;
                    }
                    if (std::memcmp(m.positions.p + 3 * v, b, 12) == 0) break;
                    s = (s + 1) & (cap - 1);
                }
                id[k] = table.p[s];
            }
            if (id[0] == id[1] || id[1] == id[2] || id[0] == id[2]) continue;  // no area
            std::memcpy(m.indices.p + m.indexCount, id, 12);
            m.indexCount += 3;
        }
        done += n;
    }
    std::fclose(f);
    return 0;
}

// Removes triangles that use the same three vertices as another: a collapse can
// leave a pair back to back (a fin with no volume), which would make the mesh
// non-manifold. Same-facing copies keep one; opposite-facing pairs cancel out.
size_t dropDuplicates(uint32_t* idx, size_t indexCount) {
    size_t t = indexCount / 3;
    struct Key { uint32_t a, b, c; uint32_t tri; bool odd; };
    Key* keys = static_cast<Key*>(std::malloc(t * sizeof(Key)));
    if (!keys) return indexCount;  // keep them all; the mesh still slices
    for (size_t i = 0; i < t; i++) {
        uint32_t v[3] = {idx[3 * i], idx[3 * i + 1], idx[3 * i + 2]};
        // Rotate so the smallest index is first; the winding then shows as the order of the other two.
        int m = v[0] < v[1] ? (v[0] < v[2] ? 0 : 2) : (v[1] < v[2] ? 1 : 2);
        uint32_t a = v[m], b = v[(m + 1) % 3], c = v[(m + 2) % 3];
        bool odd = b > c;
        keys[i] = {a, odd ? c : b, odd ? b : c, static_cast<uint32_t>(i), odd};
    }
    std::sort(keys, keys + t, [](const Key& x, const Key& y) {
        if (x.a != y.a) return x.a < y.a;
        if (x.b != y.b) return x.b < y.b;
        if (x.c != y.c) return x.c < y.c;
        return x.tri < y.tri;
    });
    unsigned char* keep = static_cast<unsigned char*>(std::malloc(t));
    if (!keep) { std::free(keys); return indexCount; }
    std::memset(keep, 1, t);
    for (size_t i = 0; i < t;) {
        size_t j = i;
        int forward = 0, backward = 0;
        while (j < t && keys[j].a == keys[i].a && keys[j].b == keys[i].b && keys[j].c == keys[i].c) {
            (keys[j].odd ? backward : forward)++;
            j++;
        }
        if (j - i > 1) {
            // Net facing left after opposite pairs cancel: keep that many (0 or 1) of the majority side.
            int net = forward - backward;
            for (size_t k = i; k < j; k++) keep[keys[k].tri] = 0;
            if (net != 0) {
                for (size_t k = i; k < j; k++) if (keys[k].odd == (net < 0)) { keep[keys[k].tri] = 1; break; }
            }
        }
        i = j;
    }
    size_t out = 0;
    for (size_t i = 0; i < t; i++) {
        if (!keep[i]) continue;
        if (out != 3 * i) std::memmove(idx + out, idx + 3 * i, 12);
        out += 3;
    }
    std::free(keep);
    std::free(keys);
    return out;
}

jlong writeStl(const char* path, const float* pos, const uint32_t* idx, size_t indexCount) {
    FILE* f = std::fopen(path, "wb");
    if (!f) return ERR_WRITE;
    unsigned char header[84] = {0};
    const char* text = "MobileSlicer simplified";
    std::memcpy(header, text, std::strlen(text));
    uint32_t count = static_cast<uint32_t>(indexCount / 3);
    std::memcpy(header + 80, &count, 4);
    bool ok = std::fwrite(header, 1, 84, f) == 84;
    unsigned char rec[50];
    for (size_t i = 0; ok && i < indexCount; i += 3) {
        const float* a = pos + 3 * idx[i];
        const float* b = pos + 3 * idx[i + 1];
        const float* c = pos + 3 * idx[i + 2];
        float u[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float v[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        float nrm[3] = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        float len = std::sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2]);
        if (len > 0) for (float& x : nrm) x /= len;
        std::memcpy(rec, nrm, 12);
        std::memcpy(rec + 12, a, 12);
        std::memcpy(rec + 24, b, 12);
        std::memcpy(rec + 36, c, 12);
        rec[48] = rec[49] = 0;
        ok = std::fwrite(rec, 1, 50, f) == 50;
    }
    if (std::fclose(f) != 0) ok = false;
    return ok ? static_cast<jlong>(count) : ERR_WRITE;
}

}  // namespace

#ifdef MESHSIMPLIFY_CLI
// Desktop command line for measuring time and memory (build-simplifier.sh cli):
//   meshsimplify IN.stl OUT.stl TARGET_TRIANGLES MAX_ERROR
int main(int argc, char** argv) {
    if (argc != 5) { std::fprintf(stderr, "usage: meshsimplify IN.stl OUT.stl TARGET_TRIANGLES MAX_ERROR\n"); return 2; }
    Stl m;
    jlong r = readAndWeld(argv[1], m);
    if (r != 0) { std::fprintf(stderr, "read failed: %lld\n", static_cast<long long>(r)); return 1; }
    Buf<uint32_t> dst;
    if (!dst.alloc(m.indexCount)) return 1;
    float err = 0;
    size_t n = meshopt_simplify(dst.p, m.indices.p, m.indexCount, m.positions.p, m.vertices, 12,
                                static_cast<size_t>(std::atoll(argv[3])) * 3, static_cast<float>(std::atof(argv[4])),
                                meshopt_SimplifyErrorAbsolute | meshopt_SimplifyLockBorder, &err);
    n = dropDuplicates(dst.p, n);
    r = writeStl(argv[2], m.positions.p, dst.p, n);
    std::printf("%u triangles, %zu vertices -> %lld triangles, error %g\n", m.triangles, m.vertices, static_cast<long long>(r), err);
    return r < 0;
}
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_org_mobileslicer_engine_NativeSimplifier_simplifyStl(
    JNIEnv* env, jclass, jstring inPath, jstring outPath, jlong targetTriangles, jfloat maxError,
    jboolean lockBorder, jfloatArray stats) {
    const char* in = env->GetStringUTFChars(inPath, nullptr);
    const char* out = env->GetStringUTFChars(outPath, nullptr);
    jlong result;
    float info[3] = {0, 0, 0};  // error reached (mesh units), input triangles, welded vertices
    try {
        Stl m;
        result = readAndWeld(in, m);
        if (result == 0) {
            info[1] = static_cast<float>(m.triangles);
            info[2] = static_cast<float>(m.vertices);
            Buf<uint32_t> dst;
            if (!dst.alloc(m.indexCount)) {
                result = ERR_MEMORY;
            } else {
                size_t target = static_cast<size_t>(targetTriangles < 0 ? 0 : targetTriangles) * 3;
                unsigned int options = meshopt_SimplifyErrorAbsolute | (lockBorder ? meshopt_SimplifyLockBorder : 0u);
                float err = 0;
                size_t n = meshopt_simplify(dst.p, m.indices.p, m.indexCount, m.positions.p, m.vertices,
                                            3 * sizeof(float), target, maxError, options, &err);
                info[0] = err;
                n = dropDuplicates(dst.p, n);
                result = writeStl(out, m.positions.p, dst.p, n);
            }
        }
    } catch (const std::bad_alloc&) {
        result = ERR_MEMORY;
    }
    env->ReleaseStringUTFChars(inPath, in);
    env->ReleaseStringUTFChars(outPath, out);
    if (stats != nullptr && env->GetArrayLength(stats) >= 3) env->SetFloatArrayRegion(stats, 0, 3, info);
    return result;
}
