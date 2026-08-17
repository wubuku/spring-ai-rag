package com.springairag.api.openai;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.springairag.api.enums.ChatMode;
import com.springairag.api.enums.CollectionScopeMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 请求的受支持子集。
 *
 * <p>未建模字段会进入 {@link #additionalProperties}，由兼容层显式拒绝，避免调用方
 * 误以为 temperature、tool calls 或 structured output 已生效。</p>
 */
public class OpenAiChatCompletionRequest {

    private String model;
    private List<Message> messages;
    private Boolean stream;
    private Integer n;
    private RagOptions rag;
    private Object temperature;
    @JsonProperty("top_p")
    private Object topP;
    @JsonProperty("max_tokens")
    private Object maxTokens;
    @JsonProperty("max_completion_tokens")
    private Object maxCompletionTokens;
    private Object tools;
    @JsonProperty("tool_choice")
    private Object toolChoice;
    private Object functions;
    @JsonProperty("function_call")
    private Object functionCall;
    private Object logprobs;
    @JsonProperty("response_format")
    private Object responseFormat;
    @JsonProperty("stream_options")
    private Object streamOptions;
    private final Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }
    public Boolean getStream() { return stream; }
    public void setStream(Boolean stream) { this.stream = stream; }
    public Integer getN() { return n; }
    public void setN(Integer n) { this.n = n; }
    public RagOptions getRag() { return rag; }
    public void setRag(RagOptions rag) { this.rag = rag; }
    public Object getTemperature() { return temperature; }
    public void setTemperature(Object temperature) { this.temperature = temperature; }
    public Object getTopP() { return topP; }
    public void setTopP(Object topP) { this.topP = topP; }
    public Object getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Object maxTokens) { this.maxTokens = maxTokens; }
    public Object getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(Object value) { this.maxCompletionTokens = value; }
    public Object getTools() { return tools; }
    public void setTools(Object tools) { this.tools = tools; }
    public Object getToolChoice() { return toolChoice; }
    public void setToolChoice(Object toolChoice) { this.toolChoice = toolChoice; }
    public Object getFunctions() { return functions; }
    public void setFunctions(Object functions) { this.functions = functions; }
    public Object getFunctionCall() { return functionCall; }
    public void setFunctionCall(Object functionCall) { this.functionCall = functionCall; }
    public Object getLogprobs() { return logprobs; }
    public void setLogprobs(Object logprobs) { this.logprobs = logprobs; }
    public Object getResponseFormat() { return responseFormat; }
    public void setResponseFormat(Object responseFormat) { this.responseFormat = responseFormat; }
    public Object getStreamOptions() { return streamOptions; }
    public void setStreamOptions(Object streamOptions) { this.streamOptions = streamOptions; }
    public Map<String, JsonNode> getAdditionalProperties() {
        return Map.copyOf(additionalProperties);
    }

    @JsonAnySetter
    public void putAdditionalProperty(String name, JsonNode value) {
        additionalProperties.put(name, value);
    }

    public static class Message {
        private String role;
        private JsonNode content;
        private String name;
        @JsonProperty("tool_calls")
        private JsonNode toolCalls;
        @JsonProperty("function_call")
        private JsonNode functionCall;
        private final Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public JsonNode getContent() { return content; }
        public void setContent(JsonNode content) { this.content = content; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public JsonNode getToolCalls() { return toolCalls; }
        public void setToolCalls(JsonNode toolCalls) { this.toolCalls = toolCalls; }
        public JsonNode getFunctionCall() { return functionCall; }
        public void setFunctionCall(JsonNode functionCall) {
            this.functionCall = functionCall;
        }
        public Map<String, JsonNode> getAdditionalProperties() {
            return Map.copyOf(additionalProperties);
        }

        @JsonAnySetter
        public void putAdditionalProperty(String field, JsonNode value) {
            additionalProperties.put(field, value);
        }
    }

    public static class RagOptions {
        private Scope scope;
        @JsonProperty("document_ids")
        private List<Long> documentIds;
        private ChatMode mode;
        private String memory;
        private final Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

        public Scope getScope() { return scope; }
        public void setScope(Scope scope) { this.scope = scope; }
        public List<Long> getDocumentIds() { return documentIds; }
        public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }
        public ChatMode getMode() { return mode; }
        public void setMode(ChatMode mode) { this.mode = mode; }
        public String getMemory() { return memory; }
        public void setMemory(String memory) { this.memory = memory; }
        public Map<String, JsonNode> getAdditionalProperties() {
            return Map.copyOf(additionalProperties);
        }

        @JsonAnySetter
        public void putAdditionalProperty(String field, JsonNode value) {
            additionalProperties.put(field, value);
        }
    }

    public static class Scope {
        private CollectionScopeMode mode;
        @JsonProperty("collection_ids")
        private List<Long> collectionIds;
        @JsonProperty("collection_keys")
        private List<String> collectionKeys;
        private final Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

        public CollectionScopeMode getMode() { return mode; }
        public void setMode(CollectionScopeMode mode) { this.mode = mode; }
        public List<Long> getCollectionIds() { return collectionIds; }
        public void setCollectionIds(List<Long> collectionIds) {
            this.collectionIds = collectionIds;
        }
        public List<String> getCollectionKeys() { return collectionKeys; }
        public void setCollectionKeys(List<String> collectionKeys) {
            this.collectionKeys = collectionKeys;
        }
        public Map<String, JsonNode> getAdditionalProperties() {
            return Map.copyOf(additionalProperties);
        }

        @JsonAnySetter
        public void putAdditionalProperty(String field, JsonNode value) {
            additionalProperties.put(field, value);
        }
    }
}
