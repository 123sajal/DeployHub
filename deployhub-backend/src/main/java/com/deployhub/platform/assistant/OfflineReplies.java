package com.deployhub.platform.assistant;

import com.deployhub.platform.models.enums.AssistantTopic;

import java.util.Locale;

/**
 * Built-in answers for when the AI isn't used: no API key, the day's budget is used up, the user's limit is reached,
 * or the AI is unreachable. They are simple but always correct, and cost nothing.
 */
public final class OfflineReplies {

    private OfflineReplies() {
    }

    /** One line saying why these are simple answers. */
    public static String notice(AssistantBudget.Limit limit) {
        if (limit == null) {
            return "The AI is not reachable right now, so here is a simple answer.";
        }
        return switch (limit) {
            case NOT_SET_UP -> "The AI assistant isn't set up on this DeployHub yet, so here is a simple answer.";
            case DAILY_BUDGET -> "The AI assistant has reached today's limit and is back after midnight (India time). "
                    + "Here is a simple answer.";
            case PERSONAL_LIMIT -> "You've used today's AI questions; they are back after midnight (India time). "
                    + "Here is a simple answer.";
        };
    }

    public static String chat(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (containsAny(q, "price", "pay", "cost", "free", "rupee", "₹", "razorpay", "upi", "credit", "paisa", "paise")) {
            return "Your first free projects cost nothing, redeploys included. Other projects are paid per deploy on the "
                    + "Payment page (UPI, cards or netbanking). Rollbacks are free, and a failed deploy gives the payment "
                    + "back as a credit. Settings shows how many free projects you have left.";
        }
        if (containsAny(q, "student", "discount", "college", "id card")) {
            return "Verified students get a discount on every paid deploy. Go to Settings > Student discount and either "
                    + "upload a photo of your student ID (it is deleted right after the check) or get a code on your "
                    + "college email address.";
        }
        if (containsAny(q, "database", "mysql", "sql", "import", "export", "db_")) {
            return "Open your backend project and click \"Add MySQL database\". DeployHub creates it and sets DB_HOST, "
                    + "DB_PORT, DB_NAME, DB_USER, DB_PASSWORD and DATABASE_URL for your app. Under \"Your data\" you can "
                    + "import a .sql file or export one. Redeploy the app after adding the database.";
        }
        if (containsAny(q, "env", "variable", "setting", "secret", ".env", "key")) {
            return "Open the project and look at \"What this app needs\": it lists the variables your app uses and can "
                    + "fill in generated secrets. You can also add any variable under Environment variables. Redeploy "
                    + "after changing them.";
        }
        if (containsAny(q, "fail", "error", "crash", "build", "not working", "broken", "log")) {
            return "Open the failed deployment (Projects > your project > Deployments). The reason is shown at the top "
                    + "with a hint, and the log shows the last lines. Common fixes: correct the Build or Start command, "
                    + "set the App folder if the app is in a sub-folder, or add missing environment variables.";
        }
        if (containsAny(q, "link", "domain", "public", "url", "share", "address")) {
            return "Every project has a fixed address on this machine. Click \"Make public\" on the project page to get "
                    + "a public https link you can share; you don't need to buy a domain. Frontends reach their backend "
                    + "through Routes (e.g. /api).";
        }
        if (containsAny(q, "password", "login", "log in", "sign in", "forgot")) {
            return "If you forgot your password, click \"Forgot password\" on the login page: DeployHub emails you a "
                    + "6-digit code to set a new one. After 5 wrong tries, wait 15 minutes or use Forgot password.";
        }
        if (containsAny(q, "deploy", "start", "how", "new project", "github", "repo")) {
            return "Click Projects > New project, paste your GitHub or GitLab repository link and press \"Detect from "
                    + "repository\" to fill in the commands. Set the App folder if your app is in a sub-folder, save, "
                    + "and click Deploy. For a frontend and backend, create two projects and add a Route for /api.";
        }
        return "I can help with deploying a project, failed deployments, environment variables, databases, public "
                + "links, payments and the student discount. Try asking about one of those.";
    }

    public static String explain(AssistantTopic topic, String errorText) {
        String reason = errorText == null || errorText.isBlank() ? "" : "DeployHub says: " + errorText.strip() + "\n\n";
        return reason + switch (topic) {
            case DEPLOYMENT_FAILED -> """
                    Things to check:
                    1. Build command and Start command: do they work in the App folder on your computer?
                    2. App folder: set it if the app is in a sub-folder (e.g. backend).
                    3. "What this app needs": add any missing environment variables, then redeploy.
                    4. The log's last lines usually name the real error (a missing file, script or package).""";
            case MISSING_SETTINGS -> "Your app reads these settings but they aren't set yet. Click \"Fill in\" in "
                    + "\"What this app needs\" to generate secrets, type in the others (for example an API key from "
                    + "another service), then redeploy.";
            case PAYMENT_FAILED -> "No money is taken for a failed payment. Try again with another method; in test mode, "
                    + "Netbanking > Success works best. If money was taken but no credit appeared, open the Payment page "
                    + "again: DeployHub checks with Razorpay and adds it.";
            case IMPORT_REFUSED -> "Check that the file is a .sql export (from mysqldump or MySQL Workbench) under 50 MB. "
                    + "If the database already has tables, tick \"Delete the current data first\" to replace them, or "
                    + "export first to keep a copy.";
        };
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
