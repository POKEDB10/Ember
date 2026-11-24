package edu.ember.client.call;

/**
 * The user's call quality setting. Each mode has a policy object that answers "how good may this
 * call be?" (Strategy pattern): the rest of the code asks the policy, never the enum.
 * @concept enums, Strategy pattern, polymorphism
 */
public enum QualityMode {
    DATA_SAVER("Data saver", new QualityPolicy.DataSaver()),
    AUTO("Auto", new QualityPolicy.Auto()),
    MAX("Max", new QualityPolicy.Max());

    private final String label;
    private final QualityPolicy policy;

    QualityMode(String label, QualityPolicy policy) {
        this.label = label;
        this.policy = policy;
    }

    public QualityPolicy policy() {
        return policy;
    }

    /** Wire and settings name, shared with the website: "datasaver", "auto", "max". */
    public String key() {
        return name().replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    public static QualityMode fromKey(String key) {
        for (QualityMode mode : values()) {
            if (mode.key().equals(key)) {
                return mode;
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return label;
    }
}
