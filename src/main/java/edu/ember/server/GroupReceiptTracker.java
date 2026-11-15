package edu.ember.server;

import edu.ember.common.model.MessageStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aggregates one recipient's delivered and read acknowledgements for a message. @concept synchronized state, sets */
public final class GroupReceiptTracker {
    private final int totalRecipients;
    private final Set<String> deliveredUsers = new HashSet<>();
    private final Set<String> readUsers = new HashSet<>();

    public GroupReceiptTracker(int totalRecipients) {
        this.totalRecipients = totalRecipients;
    }

    public synchronized ReceiptSummary record(String userId, MessageStatus status) {
        if (status == MessageStatus.READ) {
            deliveredUsers.add(userId);
            readUsers.add(userId);
        } else if (status == MessageStatus.DELIVERED) {
            deliveredUsers.add(userId);
        }
        return new ReceiptSummary(deliveredUsers.size(), readUsers.size(), totalRecipients,
                List.copyOf(readUsers));
    }

    public record ReceiptSummary(int delivered, int read, int total, List<String> readBy) {
    }
}
