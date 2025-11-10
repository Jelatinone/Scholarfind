package com.github.jelatinone;

public interface Container<Task, Result> extends Runnable {

  Result compute(final Task Work);

  void run();
}
