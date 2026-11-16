package edu.ember.client;

import edu.ember.common.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

/** Finds messages containing every query term. @concept StringTokenizer, encapsulation */
public final class SearchEngine {
    public List<Message> search(List<Message> messages, String query) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) { return List.of(); }
        List<Message> matches = new ArrayList<>();
        for (Message message : messages) {
            if (containsEveryTerm(message.body(), terms)) { matches.add(message); }
        }
        return List.copyOf(matches);
    }

    private List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) { return terms; }
        StringTokenizer tokenizer = new StringTokenizer(query);
        while (tokenizer.hasMoreTokens()) {
            terms.add(tokenizer.nextToken().toLowerCase(Locale.ROOT));
        }
        return terms;
    }

    private boolean containsEveryTerm(String body, List<String> terms) {
        String normalized = body.toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(normalized::contains);
    }
}
