package edu.ember.common.model;

import java.util.ArrayList;
import java.util.List;

/** Stores group poll choices as one vote row per member. @concept two-dimensional arrays, encapsulation */
public final class PollData {
    private final String pollId;
    private final String groupId;
    private final String question;
    private final List<String> options;
    private final List<String> memberIds;
    private final int[][] voteMatrix;

    public PollData(String pollId, String groupId, String question, List<String> options,
            List<String> memberIds) {
        if (options.size() < 2 || options.size() > 6 || memberIds.isEmpty()) {
            throw new IllegalArgumentException("Poll requires 2–6 options and group members");
        }
        this.pollId = pollId;
        this.groupId = groupId;
        this.question = question;
        this.options = List.copyOf(options);
        this.memberIds = List.copyOf(memberIds);
        voteMatrix = new int[memberIds.size()][options.size()];
    }

    public String pollId() { return pollId; }
    public String groupId() { return groupId; }
    public String question() { return question; }
    public List<String> options() { return options; }
    public List<String> memberIds() { return memberIds; }
    public synchronized int[][] voteMatrix() {
        int[][] copy = new int[voteMatrix.length][];
        for (int row = 0; row < voteMatrix.length; row++) { copy[row] = voteMatrix[row].clone(); }
        return copy;
    }

    public synchronized void vote(String userId, int optionIndex) {
        int row = memberIds.indexOf(userId);
        if (row < 0 || optionIndex < 0 || optionIndex >= options.size()) {
            throw new IllegalArgumentException("Poll vote is not valid for this member");
        }
        java.util.Arrays.fill(voteMatrix[row], 0);
        voteMatrix[row][optionIndex] = 1;
    }

    public synchronized List<Integer> totals() {
        List<Integer> totals = new ArrayList<>(options.size());
        for (int option = 0; option < options.size(); option++) {
            int total = 0;
            for (int[] row : voteMatrix) { total += row[option]; }
            totals.add(total);
        }
        return List.copyOf(totals);
    }
}
