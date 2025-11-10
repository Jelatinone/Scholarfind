package com.github.jelatinone.container;

import com.github.jelatinone.Container;
import com.github.jelatinone.Document;

public class AnnotationContainer implements Container<String, Document> {
  static volatile AnnotationContainer Instance;

  private AnnotationContainer() {

  }


  @Override
  public Document compute(String Work) {
    return null;
  }

  @Override
  public void run() {

  }

  public static synchronized AnnotationContainer getInstance()
    throws UnsupportedOperationException {
    AnnotationContainer Result = Instance;
    if (Instance == (null)) {
      synchronized (Container.class) {
        Result = Instance;
        if (Instance == (null)) {
          Instance = Result = new AnnotationContainer();
        }
      }
    }
    return Result;
  }
}
