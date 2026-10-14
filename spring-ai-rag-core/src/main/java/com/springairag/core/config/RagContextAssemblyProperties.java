package com.springairag.core.config;

public class RagContextAssemblyProperties {
    private boolean enabled = true;
    private int neighborRadius = 1;
    private int maxSeeds = 20;
    private int maxWindowCharacters = 4000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getNeighborRadius() { return neighborRadius; }
    public void setNeighborRadius(int value) { neighborRadius = value; }
    public int getMaxSeeds() { return maxSeeds; }
    public void setMaxSeeds(int value) { maxSeeds = value; }
    public int getMaxWindowCharacters() { return maxWindowCharacters; }
    public void setMaxWindowCharacters(int value) { maxWindowCharacters = value; }

    public void validate() {
        if (neighborRadius < 0 || neighborRadius > 2 || maxSeeds < 1 || maxSeeds > 40
                || maxWindowCharacters < 256 || maxWindowCharacters > 16000) {
            throw new IllegalArgumentException("Invalid rag.chat.context-assembly limits");
        }
    }
}
