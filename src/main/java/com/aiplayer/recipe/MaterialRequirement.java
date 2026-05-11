package com.aiplayer.recipe;

public final class MaterialRequirement {
    private final String item;
    private final int count;

    public MaterialRequirement(String item, int count) {
        this.item = item;
        this.count = Math.max(0, count);
    }

    public static MaterialRequirement of(String item, int count) {
        return new MaterialRequirement(item, count);
    }

    public String getItem() {
        return item;
    }

    public int getCount() {
        return count;
    }

    @Override
    public String toString() {
        return item + " x" + count;
    }
}
