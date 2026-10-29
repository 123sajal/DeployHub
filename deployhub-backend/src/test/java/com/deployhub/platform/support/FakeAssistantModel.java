package com.deployhub.platform.support;

import com.deployhub.platform.assistant.AssistantModel;
import com.deployhub.platform.assistant.AssistantUnavailableException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Stands in for Claude: records what DeployHub would send, and answers whatever the test sets. No network, no cost. */
public class FakeAssistantModel implements AssistantModel {

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public final List<byte[]> idCards = new CopyOnWriteArrayList<>();
    public volatile Function<Request, Reply> answer;
    public volatile IdCardResult idCardResult;
    public volatile boolean down;

    public FakeAssistantModel() {
        reset();
    }

    public void reset() {
        requests.clear();
        idCards.clear();
        down = false;
        answer = request -> new Reply("Here is what to do.", new Usage(1000, 200), false, false);
        idCardResult = card(true, true, "Alice Example", "Test University", "2027-06-30", "");
    }

    public static IdCardResult card(boolean readable, boolean studentIdCard, String name, String institution,
                                    String validUntil, String problem) {
        return new IdCardResult(new IdCardReading(readable, studentIdCard, name, institution, validUntil, problem),
                new Usage(1500, 100), false);
    }

    @Override
    public Reply ask(Request request) {
        requests.add(request);
        if (down) {
            throw new AssistantUnavailableException("fake outage");
        }
        return answer.apply(request);
    }

    @Override
    public IdCardResult readStudentId(String model, byte[] jpeg) {
        idCards.add(jpeg);
        if (down) {
            throw new AssistantUnavailableException("fake outage");
        }
        return idCardResult;
    }
}
