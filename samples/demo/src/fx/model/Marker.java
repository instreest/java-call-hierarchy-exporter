package fx.model;

public @interface Marker {
    String NAME = Registry.class.getName();
    String value() default "m";
}
