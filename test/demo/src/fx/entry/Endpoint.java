package fx.entry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 自前のフレームワークが入口の印に使うアノテーション（契約表で足す題材。#136 段階3） */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Endpoint {
}
