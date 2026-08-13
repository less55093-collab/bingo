export interface UrlCitationAnnotation {
  type: "url_citation";
  title: string;
  url: string;
}

export interface GenerationInterruptedAnnotation {
  type: "generation_interrupted";
}

/**
 * Union type for message annotations
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessageAnnotation
 */
export type UIMessageAnnotation = UrlCitationAnnotation | GenerationInterruptedAnnotation;
