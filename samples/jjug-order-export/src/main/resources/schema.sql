create table orders (
  id            bigint primary key,
  customer_name varchar(100) not null,
  order_date    date not null,
  amount        decimal(10, 0) not null
);
