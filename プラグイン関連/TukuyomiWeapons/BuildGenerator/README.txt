ビルド登録ジェネレーター
========================

ビルド申請のExcelから、tukuyomi_build.sk に書く「名前→BuildID」と「BuildIDの中身」の行を自動で作ります。

準備(最初の1回だけ)
  1. Python 3.8以上を入れる (https://www.python.org/ ・インストール時に "Add python.exe to PATH" にチェック)
  2. コマンドプロンプトで   pip install openpyxl

使い方
  方法A: tukuyomi_build.sk を直接書き換える(おすすめ)
    申請のxlsx と tukuyomi_build.sk の2つを、make_build.bat にドラッグ&ドロップする。
    → tukuyomi_build.sk の中の自動生成範囲が書き換わる(元のファイルは tukuyomi_build.sk.bak に残る)
    → サーバーで  /sk reload tukuyomi_build

  方法B: 貼り付け用のテキストだけ作る
    xlsx だけを make_build.bat にドラッグ&ドロップする。
    → xlsx と同じ場所に build_registry.txt ができる。中身を tukuyomi_build.sk の on load: の中に貼る。

  コマンドで実行する場合:
    python make_build.py 申請.xlsx tukuyomi_build.sk

tukuyomi_build.sk の自動生成範囲
  次の2行にはさまれた部分が、毎回まるごと書き換わります。この範囲は手で直さないでください。
    # >>> GENERATED-BUILDS START
    # <<< GENERATED-BUILDS END
  (範囲の外にある設定や、ほかの関数は変わりません)

Excelの前提
  ・シート名「フォームの回答 1」。列の並びは A:タイムスタンプ / B:プレイヤー名 / C:防具 / D:盾 / E:エリトラ /
    F:バニラ武器 / G:矢 / H:メイン / I:サブ ... T:判定 / U:ウルト / V:BuildID(数式)
  ・列の並びを変えたときは、make_build.py の C_TIME などの数字(列の位置、0始まり)を直す。
  ・BuildID(V列)が空なら、Excelの数式と同じ規則(行番号-1)で BUILD-001 のように振る。
  ・U列(ウルト)が空なら、「ウルト対応表」シートからメイン武器で引く。

自動でやってくれること
  ・プレイヤー名は小文字にし、空白は _ にする。統合版は先頭の「.」もそのまま登録名になる。
  ・同じ名前の申請が複数あるときは、新しい申請を採用する(注意として表示)。
  ・Excelの判定が「コストオーバー」のビルドは、注意として表示する(登録はされる)。
  ・矢が「使用する」のときは vanilla:arrow:64 を入れる。

エラーになるとき
  ・知らない武器・防具・バニラ武器の名前があると、エラーとして表示して、tukuyomi_build.sk は書き換えません。
  ・新しい武器やアイテムが増えたら、make_build.py の先頭にある対応表(CUSTOM / ULT / ARMOR / VANILLA)に1行足してください。
    例:  "新しい武器": "weapon_id",   ← 右側は TukuyomiWeapons の武器ID

同梱ファイル
  make_build.py                       本体
  make_build.bat                      Windows用(ドラッグ&ドロップ)
ビルドのスプレッドシート、ここからxlsx出力をする
　https://docs.google.com/spreadsheets/d/12v7hSvgB5WQq2JKcUjXfugAiJWrOQTmln75Cmv7a6-E/edit?usp=sharing