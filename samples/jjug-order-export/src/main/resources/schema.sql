create table orders (
  id            bigint primary key,
  customer_name varchar(100) not null,
  order_date    date not null,
  amount        decimal(10, 0) not null
);

create table partners (
  partner_code  varchar(10) primary key,
  partner_name  varchar(100) not null,
  export_format varchar(10) not null
);
