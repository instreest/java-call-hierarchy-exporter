package fx.model;

public record Point(int x, int y) implements Shape {
    public Point {
        Registry.register("p");
    }

    @Override
    public double area() { return x * y; }

    public static Point origin() { return new Point(0, 0); }
}
