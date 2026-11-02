#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include <thread>
#include "llama.h"

#define LOG_TAG "AIStudioNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static bool g_backend_ready = false;
static uint32_t g_context_size = 4096;
static int g_thread_count = 4;

static std::string fromJString(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static jstring toJString(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fzdff_aistudio_LocalModelEngine_nativeLoad(
        JNIEnv * env, jobject, jstring path, jint contextSize, jint threads) {
    std::lock_guard<std::mutex> guard(g_mutex);
    const std::string modelPath = fromJString(env, path);
    if (modelPath.empty()) return toJString(env, "ERROR: 模型路径为空");
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }

    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_context_size = static_cast<uint32_t>(std::max(1024, std::min(16384, static_cast<int>(contextSize))));
    g_thread_count = std::max(2, std::min(8, static_cast<int>(threads)));
    g_model = llama_model_load_from_file(modelPath.c_str(), params);
    if (!g_model) {
        LOGE("Could not load model: %s", modelPath.c_str());
        return toJString(env, "ERROR: 无法加载 GGUF。请检查文件是否完整、格式是否受支持，以及手机是否有足够内存。");
    }
    return toJString(env, "OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fzdff_aistudio_LocalModelEngine_nativeGenerate(
        JNIEnv * env, jobject, jstring promptValue, jint maxTokens, jfloat temperature) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_model) return toJString(env, "ERROR: 请先加载本地模型");
    const std::string prompt = fromJString(env, promptValue);
    if (prompt.empty()) return toJString(env, "ERROR: 提示词为空");

    auto ctxParams = llama_context_default_params();
    const uint32_t contextSize = g_context_size;
    ctxParams.n_ctx = contextSize;
    ctxParams.n_batch = 512;
    ctxParams.n_ubatch = 512;
    ctxParams.n_threads = g_thread_count;
    ctxParams.n_threads_batch = g_thread_count;

    llama_context * ctx = llama_init_from_model(g_model, ctxParams);
    if (!ctx) return toJString(env, "ERROR: 无法创建推理上下文。请尝试降低上下文长度或换用更小的模型。");

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(std::max<size_t>(prompt.size() + 32, 256));
    int32_t tokenCount = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    if (tokenCount < 0) {
        tokens.resize(static_cast<size_t>(-tokenCount) + 32);
        tokenCount = llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    }
    if (tokenCount <= 0) {
        llama_free(ctx);
        return toJString(env, "ERROR: 无法对输入文本进行分词");
    }
    tokens.resize(static_cast<size_t>(tokenCount));

    const int maxOutput = std::max(1, std::min(8192, static_cast<int>(maxTokens)));
    const size_t promptLimit = contextSize > static_cast<uint32_t>(maxOutput + 8)
        ? contextSize - static_cast<uint32_t>(maxOutput + 8) : contextSize / 2;
    if (tokens.size() > promptLimit) {
        tokens.erase(tokens.begin(), tokens.end() - static_cast<long>(promptLimit));
    }

    bool failed = false;
    const int batchLimit = 512;
    for (size_t offset = 0; offset < tokens.size();) {
        const int count = static_cast<int>(std::min<size_t>(batchLimit, tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(ctx, batch) != 0) {
            failed = true;
            break;
        }
        offset += static_cast<size_t>(count);
    }
    if (failed) {
        llama_free(ctx);
        return toJString(env, "ERROR: 模型无法处理当前输入。请减少上下文或更换模型。");
    }

    auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.05f, std::min(1.5f, static_cast<float>(temperature)))));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    char piece[2048];
    for (int i = 0; i < maxOutput; ++i) {
        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        int pieceSize = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (pieceSize < 0) {
            std::vector<char> largePiece(static_cast<size_t>(-pieceSize) + 16);
            pieceSize = llama_token_to_piece(vocab, token, largePiece.data(),
                                             static_cast<int32_t>(largePiece.size()), 0, true);
            if (pieceSize > 0) output.append(largePiece.data(), static_cast<size_t>(pieceSize));
        } else if (pieceSize > 0) {
            output.append(piece, static_cast<size_t>(pieceSize));
        }

        llama_token next = token;
        llama_batch nextBatch = llama_batch_get_one(&next, 1);
        if (llama_decode(ctx, nextBatch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    if (output.empty()) return toJString(env, "ERROR: 模型没有生成文本。请确认导入的是聊天模型而不是 Embedding 模型。");
    return toJString(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fzdff_aistudio_LocalModelEngine_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}
