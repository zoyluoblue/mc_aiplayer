package com.aiplayer.memory;

import com.aiplayer.agent.AgentSkillLibrary;
import com.aiplayer.entity.AiPlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class AiPlayerMemory {
    private final AiPlayerEntity aiPlayer;
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private final AgentSkillLibrary skillLibrary;
    private static final int MAX_RECENT_ACTIONS = 20;

    public AiPlayerMemory(AiPlayerEntity aiPlayer) {
        this.aiPlayer = aiPlayer;
        this.currentGoal = "";
        this.taskQueue = new LinkedList<>();
        this.recentActions = new LinkedList<>();
        this.skillLibrary = new AgentSkillLibrary();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public void setCurrentGoal(String goal) {
        this.currentGoal = goal;
    }

    public void addAction(String action) {
        recentActions.addLast(action);
        if (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
    }

    public List<String> getRecentActions(int count) {
        int size = Math.min(count, recentActions.size());
        List<String> result = new ArrayList<>();
        
        int startIndex = Math.max(0, recentActions.size() - count);
        for (int i = startIndex; i < recentActions.size(); i++) {
            result.add(recentActions.get(i));
        }
        
        return result;
    }

    public AgentSkillLibrary getSkillLibrary() {
        return skillLibrary;
    }

    public void clearTaskQueue() {
        taskQueue.clear();
        currentGoal = "";
    }

    public void saveToNBT(CompoundTag tag) {
        tag.putString("CurrentGoal", currentGoal);
        
        ListTag actionsList = new ListTag();
        for (String action : recentActions) {
            actionsList.add(StringTag.valueOf(action));
        }
        tag.put("RecentActions", actionsList);
        tag.putString("SkillLibrary", skillLibrary.toJson());
    }

    public void loadFromNBT(CompoundTag tag) {
        if (tag.contains("CurrentGoal")) {
            currentGoal = tag.getString("CurrentGoal");
        }
        
        if (tag.contains("RecentActions")) {
            recentActions.clear();
            ListTag actionsList = tag.getList("RecentActions", 8);
            for (int i = 0; i < actionsList.size(); i++) {
                recentActions.add(actionsList.getString(i));
            }
        }
        if (tag.contains("SkillLibrary")) {
            skillLibrary.loadJson(tag.getString("SkillLibrary"));
        }
    }
}
