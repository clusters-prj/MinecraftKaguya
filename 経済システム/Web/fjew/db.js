const mariadb = require('mariadb');

const pool = mariadb.createPool({
     host: process.env.DB_HOST || '10.2.1.27', 
     user: process.env.DB_USER || 'f-apache',
     password: process.env.DB_PASSWORD, 
     database: process.env.DB_DATABASE || 'fjeconomy',
     connectionLimit: parseInt(process.env.DB_CONNECTION_LIMIT || '10', 10), // 数値に変換
     acquireTimeout: 10000
});

module.exports = pool;
