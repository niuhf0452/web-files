require('buffer');
var fs = require('fs');
var path = require('path');
var express = require('express');
var multipart = require('connect-multiparty');
var app = express();
var home = getHome();

app.use(express.static(path.resolve(__dirname, '..', 'web')));

function json(req, resp, callback) {
  var data = new Buffer(0);
  req.on('data', function (buf) {
    data = Buffer.concat([data, buf]);
  });
  req.on('end', function () {
    try {
      var args = JSON.parse(data.toString());
      if (args)
        callback(args);
      else
        resp.status(400).send(); // bad request
    } catch (ex) {
      resp.status(500).send(); // server internal error
      console.error(ex.stack || ex);
    }
  });
}

app.post('/ls', function (req, resp) {
  json(req, resp, function (args) {
    var p = args.path;
    if (!p) {
      resp.status(404).send();
      return;
    }
    var normalPath = p.replace(/^~/, function () {
      return home;
    });
    p = path.sep == '\\' ? normalPath.replace(/\//g, '\\') : normalPath;
    fs.readdir(p, function (err, files) {
      if (err) {
        resp.status(500).send(err.message);
      } else {
        var arr = [];
        for (var i = 0; i < files.length; i++) {
          statFile(files[i]);
        }
        if (files.length == 0) {
          resp.send({
            path: normalPath,
            hasParent: path.resolve(p, '..') != p,
            files: []
          });
        }

        function statFile(name) {
          fs.lstat(path.resolve(p, name), function (err, stat) {
            arr.push({
              name: name,
              size: stat.size,
              date: stat.ctime.getTime(),
              type: path.extname(name),
              isSymbolLink: stat.isSymbolicLink(),
              isDir: stat.isDirectory()
            });
            if (arr.length == files.length) {
              resp.send({
                path: normalPath,
                hasParent: path.resolve(p, '..') != p,
                files: arr
              });
            }
          });
        }
      }
    });
  });
});

app.post('/rm', function (req, resp) {
  json(req, resp, function (args) {
    var arr = args;
    for (var i = 0; i < arr.length; i++) {
      fs.unlink(arr[i]);
    }
    resp.send({
      success: true
    });
  });
});

app.post('/download', function (req, resp) {
  var data = new Buffer(0);
  req.on('data', function (buf) {
    data = Buffer.concat([data, buf]);
  });
  req.on('end', function () {
    try {
      var formData = data.toString();
      if (!/^file=/.test(formData)) {
        resp.status(400).send(); // bad request
        return;
      }
      formData = formData.slice("file=".length);
      var args = JSON.parse(formData);
      if (Array.isArray(args)) {

      } else {
        var file = args;
        resp.setHeader('Content-disposition', 'attachment; filename=' + path.basename(file));
        resp.setHeader('Content-type', 'application/octet-stream');
        var fileStream = fs.createReadStream(file);
        fileStream.pipe(resp);
      }
    } catch (ex) {
      resp.status(500).send(); // server internal error
      console.error(ex.stack || ex);
    }
  });
});

app.post('/upload', multipart());
app.post('/upload', function (req, resp) {
  // don't forget to delete all req.files when done
  var targetDir = req.body.targetDir;
  if (targetDir) {
    var errors = [];
    var count = 0;
    for (var field in req.files) {
      var file = req.files[field];
      var to = path.resolve(targetDir, file.originalFilename);
      count++;
      fs.rename(file.path, to, function (err) {
        if (err) {
          errors.push(err.message);
        }
        count--;
        if (count == 0) {
          if (errors.length == 0) {
            resp.send({
              success: true,
            });
          } else {
            resp.status(500).send(errors.join('\r\n'));
          }
        }
      });
    }
    if (count == 0) {
      resp.send({
        success: true
      });
    }
  } else {
    for (var field in req.files) {
      var file = req.files[field];
      fs.unlink(file.path);
    }
    resp.status(400).send();
  }
});

var server = app.listen(8000, function () {
  var addr = server.address();
  console.log('web-files app listening at http://%s:%s', addr.address, addr.port);
});

function getHome() {
  return process.platform == 'win32' ?
    process.env['USERPROFILE'].replace(/\\/g, '/') :
    process.env['HOME'];
}