package com.github.jelatinone;

public interface Task<Input, Result> extends Runnable {

  Result node(final Input Work);
}
