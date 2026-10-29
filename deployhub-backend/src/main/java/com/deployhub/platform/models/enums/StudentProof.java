package com.deployhub.platform.models.enums;

/** How a student was verified. */
public enum StudentProof {
    /** A photo of the student ID card, read by the AI (the photo itself is never stored). */
    ID_CARD,
    /** A code sent to a college email address (e.g. name@college.ac.in). */
    COLLEGE_EMAIL
}
