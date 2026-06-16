const mariadb = require('mariadb');

const pool = mariadb.createPool({
     host: '10.2.1.27', 
     user: 'f-apache',       // 環境に合わせて変更してください
     password: '[masked]', // 環境に合わせて変更してください
     database: 'fjeconomy',
     connectionLimit: 10,     // システム概要書の pool_size: 10 に準拠
     acquireTimeout: 10000
});

module.exports = pool;

